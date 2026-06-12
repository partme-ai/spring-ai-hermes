/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesApi.ChatRequest;
import io.github.partmeai.hermes.api.HermesApi.Message.Role;
import io.github.partmeai.hermes.api.HermesApi.Message.ToolCall;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ChatModel} implementation for Hermes Gateway.
 * <p>
 * Hermes is an AI agent gateway that exposes OpenAI-compatible
 * {@code /v1/chat/completions} and {@code /v1/embeddings} endpoints.
 * It routes requests to configured agents with support for tool calling,
 * streaming, and session management.
 * <p>
 * The {@code model} field uses Hermes agent-target routing
 * ({@code hermes/default}, {@code hermes/<agentId>}).
 * Use {@link HermesChatOptions#setXOpenclawModel(String)} to override
 * the backend provider/model for a given agent.
 *
 * @author Loong Wan
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api">Hermes OpenAI HTTP API</a>
 */
public class HermesChatModel implements ChatModel {

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
			new DefaultChatModelObservationConvention();

	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER =
			ToolCallingManager.builder().build();

	private final HermesApi chatApi;

	private final HermesChatOptions defaultOptions;

	private final ObservationRegistry observationRegistry;

	private final ToolCallingManager toolCallingManager;

	private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final RetryTemplate retryTemplate;

	public HermesChatModel(HermesApi hermesApi, HermesChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry) {
		this(hermesApi, defaultOptions, toolCallingManager, observationRegistry,
				new DefaultToolExecutionEligibilityPredicate(), RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	public HermesChatModel(HermesApi hermesApi, HermesChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry,
			ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate,
			RetryTemplate retryTemplate) {

		Assert.notNull(hermesApi, "hermesApi must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		this.chatApi = hermesApi;
		this.defaultOptions = defaultOptions;
		this.toolCallingManager = toolCallingManager;
		this.observationRegistry = observationRegistry;
		this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
		this.retryTemplate = retryTemplate;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Build {@link ChatResponseMetadata} from an OpenAI-compatible chat response.
	 */
	static ChatResponseMetadata from(HermesApi.ChatResponse response, ChatResponse previousChatResponse) {
		Assert.notNull(response, "HermesApi.ChatResponse must not be null");

		DefaultUsage newUsage = getDefaultUsage(response);
		Integer promptTokens = newUsage.getPromptTokens();
		Integer generationTokens = newUsage.getCompletionTokens();
		int totalTokens = newUsage.getTotalTokens();

		if (previousChatResponse != null && previousChatResponse.getMetadata() != null
				&& previousChatResponse.getMetadata().getUsage() != null) {
			promptTokens += previousChatResponse.getMetadata().getUsage().getPromptTokens();
			generationTokens += previousChatResponse.getMetadata().getUsage().getCompletionTokens();
			totalTokens += previousChatResponse.getMetadata().getUsage().getTotalTokens();
		}

		DefaultUsage aggregatedUsage = new DefaultUsage(promptTokens, generationTokens, totalTokens);

		String finishReason = null;
		if (response.choices() != null && !response.choices().isEmpty()) {
			finishReason = response.choices().get(0).finishReason();
		}

		return ChatResponseMetadata.builder()
			.usage(aggregatedUsage)
			.model(response.model())
			.keyValue("finish_reason", finishReason)
			.keyValue("id", response.id())
			.keyValue("created", response.created())
			.build();
	}

	private static DefaultUsage getDefaultUsage(HermesApi.ChatResponse response) {
		if (response.usage() != null) {
			return new DefaultUsage(
				Optional.ofNullable(response.usage().promptTokens()).orElse(0),
				Optional.ofNullable(response.usage().completionTokens()).orElse(0));
		}
		return new DefaultUsage(0, 0);
	}

	/**
	 * Extract the first choice's message content from an API response.
	 */
	private static String getResponseContent(HermesApi.ChatResponse response) {
		if (response.choices() == null || response.choices().isEmpty()) {
			return "";
		}
		var choice = response.choices().get(0);
		HermesApi.Message msg = choice.message() != null ? choice.message() : choice.delta();
		return msg != null && msg.content() != null ? msg.content() : "";
	}

	/**
	 * Extract tool calls from the first choice of an API response.
	 */
	private static List<HermesApi.Message.ToolCall> getResponseToolCalls(HermesApi.ChatResponse response) {
		if (response.choices() == null || response.choices().isEmpty()) {
			return List.of();
		}
		var choice = response.choices().get(0);
		HermesApi.Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) {
			return List.of();
		}
		return msg.toolCalls();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalCall(requestPrompt, null);
	}

	private ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

		HermesApi.ChatRequest request = hermesChatRequest(prompt, false);
		Map<String, String> headers = hermesHttpHeaders(prompt);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(HermesApiConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
					() -> observationContext, this.observationRegistry)
			.observe(() -> {

				HermesApi.ChatResponse hermesResponse =
						this.retryTemplate.execute(ctx -> this.chatApi.chat(request, headers));

				List<AssistantMessage.ToolCall> toolCalls = getResponseToolCalls(hermesResponse)
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
							toolCall.type() != null ? toolCall.type() : "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

				var assistantMessage = AssistantMessage.builder()
					.content(getResponseContent(hermesResponse))
					.properties(Map.of())
					.toolCalls(toolCalls)
					.build();

				String finishReason = null;
				if (hermesResponse.choices() != null && !hermesResponse.choices().isEmpty()) {
					finishReason = hermesResponse.choices().get(0).finishReason();
				}

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
					.finishReason(finishReason)
					.build();

				var generator = new Generation(assistantMessage, generationMetadata);
				ChatResponse chatResponse = new ChatResponse(List.of(generator),
						from(hermesResponse, previousChatResponse));

				observationContext.setResponse(chatResponse);
				return chatResponse;
			});

		if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
			var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
			if (toolExecutionResult.returnDirect()) {
				return ChatResponse.builder()
					.from(response)
					.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
					.build();
			}
			else {
				return this.internalCall(
						new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()), response);
			}
		}

		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalStream(requestPrompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			HermesApi.ChatRequest request = hermesChatRequest(prompt, true);
			Map<String, String> headers = hermesHttpHeaders(prompt);

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(HermesApiConstants.PROVIDER_NAME)
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
					() -> observationContext, this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(
					ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<HermesApi.ChatResponse> hermesResponse =
					this.chatApi.streamingChat(request, headers);

			Flux<ChatResponse> chatResponse = hermesResponse.map(chunk -> {
				String content = getResponseContent(chunk);

				List<AssistantMessage.ToolCall> toolCalls = getResponseToolCalls(chunk)
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
							toolCall.type() != null ? toolCall.type() : "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

				var assistantMessage = AssistantMessage.builder()
					.content(content)
					.properties(Map.of())
					.toolCalls(toolCalls)
					.build();

				String finishReason = null;
				if (chunk.choices() != null && !chunk.choices().isEmpty()) {
					finishReason = chunk.choices().get(0).finishReason();
				}

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
					.finishReason(finishReason)
					.build();

				var generator = new Generation(assistantMessage, generationMetadata);
				return new ChatResponse(List.of(generator), from(chunk, previousChatResponse));
			});

			Flux<ChatResponse> chatResponseFlux = chatResponse.flatMap(response -> {
				if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(
						prompt.getOptions(), response)) {
					return Flux.deferContextual(ctx -> {
						ToolExecutionResult toolExecutionResult;
						try {
							ToolCallReactiveContextHolder.setContext(ctx);
							toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
						}
						finally {
							ToolCallReactiveContextHolder.clearContext();
						}
						if (toolExecutionResult.returnDirect()) {
							return Flux.just(ChatResponse.builder().from(response)
								.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
								.build());
						}
						else {
							return this.internalStream(
								new Prompt(toolExecutionResult.conversationHistory(),
										prompt.getOptions()), response);
						}
					}).subscribeOn(Schedulers.boundedElastic());
				}
				else {
					return Flux.just(response);
				}
			})
			.doOnError(observation::error)
			.doFinally(s -> observation.stop())
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

			return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
		});
	}

	Prompt buildRequestPrompt(Prompt prompt) {
		HermesChatOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof HermesChatOptions ocOpts) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(
						HermesChatOptions.fromOptions(ocOpts),
						HermesChatOptions.class, HermesChatOptions.class);
			}
			else if (prompt.getOptions() instanceof ToolCallingChatOptions tcOpts) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(tcOpts,
						ToolCallingChatOptions.class, HermesChatOptions.class);
			}
			else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(),
						ChatOptions.class, HermesChatOptions.class);
			}
		}

		HermesChatOptions requestOptions = ModelOptionsUtils.merge(
				runtimeOptions, this.defaultOptions, HermesChatOptions.class);

		if (runtimeOptions != null) {
			requestOptions.setInternalToolExecutionEnabled(ModelOptionsUtils.mergeOption(
				runtimeOptions.getInternalToolExecutionEnabled(),
				this.defaultOptions.getInternalToolExecutionEnabled()));
			requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(
				runtimeOptions.getToolNames(), this.defaultOptions.getToolNames()));
			requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(
				runtimeOptions.getToolCallbacks(), this.defaultOptions.getToolCallbacks()));
			requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(
				runtimeOptions.getToolContext(), this.defaultOptions.getToolContext()));
		}
		else {
			requestOptions.setInternalToolExecutionEnabled(
					this.defaultOptions.getInternalToolExecutionEnabled());
			requestOptions.setToolNames(this.defaultOptions.getToolNames());
			requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
			requestOptions.setToolContext(this.defaultOptions.getToolContext());
		}

		if (!StringUtils.hasText(requestOptions.getModel())) {
			throw new IllegalArgumentException("model cannot be null or empty");
		}

		ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());
		return new Prompt(prompt.getInstructions(), requestOptions);
	}

	/**
	 * Package access for testing.
	 */
	HermesApi.ChatRequest hermesChatRequest(Prompt prompt, boolean stream) {

		List<HermesApi.Message> hermesMessages = prompt.getInstructions().stream()
			.flatMap(message -> {
				if (message.getMessageType() == MessageType.SYSTEM) {
					return List.of(HermesApi.Message.builder(Role.SYSTEM)
						.content(message.getText()).build()).stream();
				}
				else if (message.getMessageType() == MessageType.USER) {
					var builder = HermesApi.Message.builder(Role.USER)
						.content(message.getText());
					return List.of(builder.build()).stream();
				}
				else if (message.getMessageType() == MessageType.ASSISTANT) {
					var assistantMessage = (AssistantMessage) message;
					List<HermesApi.Message.ToolCall> toolCalls = null;
					if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
						toolCalls = assistantMessage.getToolCalls().stream()
							.map(toolCall -> {
								var function = new HermesApi.Message.ToolCallFunction(
										toolCall.name(), toolCall.arguments());
								return new HermesApi.Message.ToolCall(
										toolCall.id(), "function", function);
							}).toList();
					}
					return List.of(HermesApi.Message.builder(Role.ASSISTANT)
						.content(assistantMessage.getText())
						.toolCalls(toolCalls)
						.build()).stream();
				}
				else if (message.getMessageType() == MessageType.TOOL) {
					ToolResponseMessage toolMessage = (ToolResponseMessage) message;
					return toolMessage.getResponses().stream()
						.map(tr -> HermesApi.Message.builder(Role.TOOL)
							.content(tr.responseData())
							.toolCallId(tr.id())
							.name(tr.name())
							.build());
				}
				throw new IllegalArgumentException("Unsupported message type: "
						+ message.getMessageType());
			}).toList();

		HermesChatOptions requestOptions;
		if (prompt.getOptions() instanceof HermesChatOptions ocOpts) {
			requestOptions = ocOpts;
		}
		else {
			requestOptions = HermesChatOptions.fromOptions(
					(HermesChatOptions) prompt.getOptions());
		}

		HermesApi.ChatRequest.Builder requestBuilder = HermesApi.ChatRequest
			.builder(requestOptions.getModel())
			.stream(stream)
			.messages(hermesMessages)
			.temperature(requestOptions.getTemperature())
			.topP(requestOptions.getTopP())
			.frequencyPenalty(requestOptions.getFrequencyPenalty())
			.presencePenalty(requestOptions.getPresencePenalty())
			.seed(requestOptions.getSeed());

		if (requestOptions.getMaxTokens() != null) {
			requestBuilder.maxCompletionTokens(requestOptions.getMaxTokens());
		}

		if (requestOptions.getStop() != null && !requestOptions.getStop().isEmpty()) {
			requestBuilder.stop(requestOptions.getStop());
		}

		if (requestOptions.getUser() != null) {
			requestBuilder.user(requestOptions.getUser());
		}

		List<ToolDefinition> toolDefinitions = this.toolCallingManager
				.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			requestBuilder.tools(getTools(toolDefinitions));
		}

		return requestBuilder.build();
	}

	/**
	 * Extract x-hermes-* HTTP headers from prompt options.
	 */
	private Map<String, String> hermesHttpHeaders(Prompt prompt) {
		if (prompt.getOptions() instanceof HermesChatOptions ocOpts) {
			return ocOpts.toHttpHeaders();
		}
		return Map.of();
	}

	private List<ChatRequest.Tool> getTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new ChatRequest.Tool.Function(
					toolDefinition.name(), toolDefinition.description(),
					ModelOptionsUtils.jsonToMap(toolDefinition.inputSchema()));
			return new ChatRequest.Tool(function);
		}).toList();
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return HermesChatOptions.fromOptions(this.defaultOptions);
	}

	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	public static final class Builder {

		private HermesApi hermesApi;

		private HermesChatOptions defaultOptions = HermesChatOptions.builder()
				.model(HermesModel.DEFAULT.id()).build();

		private ToolCallingManager toolCallingManager;

		private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate =
				new DefaultToolExecutionEligibilityPredicate();

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private Builder() {
		}

		public Builder hermesApi(HermesApi hermesApi) {
			this.hermesApi = hermesApi;
			return this;
		}

		public Builder defaultOptions(HermesChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder toolExecutionEligibilityPredicate(
				ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
			this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public HermesChatModel build() {
			if (this.toolCallingManager != null) {
				return new HermesChatModel(this.hermesApi, this.defaultOptions,
						this.toolCallingManager, this.observationRegistry,
						this.toolExecutionEligibilityPredicate, this.retryTemplate);
			}
			return new HermesChatModel(this.hermesApi, this.defaultOptions,
					DEFAULT_TOOL_CALLING_MANAGER, this.observationRegistry,
					this.toolExecutionEligibilityPredicate, this.retryTemplate);
		}
	}
}
