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
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
import io.github.partmeai.hermes.api.HermesApiHelper;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Hermes API Server 的 {@link ChatModel} 实现。
 *
 * <p>将 Spring AI 的提示、模型选项与工具定义转换为 OpenAI 兼容的
 * {@code /v1/chat/completions} 请求，并提供同步、异步及流式三种调用方式。模型在
 * 需要执行工具时递归提交包含工具结果的新提示，同时累计跨轮次的用量元数据。</p>
 *
 * <p>Hermes 特有能力包括：</p>
 * <ul>
 *   <li>{@code X-Hermes-Session-Key}：稳定的通道级长期记忆作用域</li>
 *   <li>{@code X-Hermes-Session-Id}：会话记录级标识</li>
 *   <li>通过消息内容数组表达内联图片</li>
 *</ul>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@Slf4j
public class HermesChatModel implements ChatModel {

	/** 默认同步调用重试模板；最多尝试一次，因此不会自动重发请求。 */
	private static final RetryTemplate DEFAULT_RETRY_TEMPLATE = RetryTemplate.builder().maxAttempts(1).build();

	/** 未配置自定义约定时使用的 Spring AI 聊天模型观测约定。 */
	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
			new DefaultChatModelObservationConvention();

	/** 构建器未提供工具管理器时使用的默认工具调用管理器。 */
	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER =
			ToolCallingManager.builder().build();

	/** 底层 Hermes HTTP API 客户端。 */
	private final HermesApi chatApi;

	/** 与每次运行时选项合并的默认聊天选项。 */
	private final HermesChatOptions defaultOptions;

	/** 创建聊天模型观测的 Micrometer 注册表。 */
	private final ObservationRegistry observationRegistry;

	/** 解析工具定义并执行模型工具调用的管理器。 */
	private final ToolCallingManager toolCallingManager;

	/** 判断当前响应是否需要执行工具的策略。 */
	private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

	/** 当前聊天模型观测命名约定，可在构造后替换。 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	/** 仅用于同步 Hermes HTTP 调用的重试模板。 */
	private final RetryTemplate retryTemplate;

	/**
	 * 使用默认工具执行资格判断器和默认单次尝试重试策略创建聊天模型。
	 *
	 * @param api Hermes API 客户端
	 * @param defaultOptions 默认聊天选项
	 * @param toolCallingManager 工具定义解析与调用管理器
	 * @param observationRegistry Micrometer 观测注册表
	 * @throws IllegalArgumentException 任一参数为 {@code null} 时抛出
	 */
	public HermesChatModel(HermesApi api, HermesChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry) {
		this(api, defaultOptions, toolCallingManager, observationRegistry,
				new DefaultToolExecutionEligibilityPredicate(), DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * 使用完整依赖创建聊天模型。
	 *
	 * @param api Hermes API 客户端
	 * @param defaultOptions 默认聊天选项
	 * @param toolCallingManager 工具定义解析与调用管理器
	 * @param observationRegistry Micrometer 观测注册表
	 * @param toolExecutionEligibilityPredicate 工具执行资格判断器
	 * @param retryTemplate 同步 HTTP 调用的重试模板
	 * @throws IllegalArgumentException 任一参数为 {@code null} 时抛出
	 */
	public HermesChatModel(HermesApi api, HermesChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry,
			ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate,
			RetryTemplate retryTemplate) {

		Assert.notNull(api, "api must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		this.chatApi = api;
		this.defaultOptions = defaultOptions;
		this.toolCallingManager = toolCallingManager;
		this.observationRegistry = observationRegistry;
		this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
		this.retryTemplate = retryTemplate;
	}

	/**
	 * 创建 Hermes 聊天模型构建器。
	 *
	 * @return 新构建器
	 */
	public static Builder builder() { return new Builder(); }

	static ChatResponseMetadata from(HermesApi.ChatResponse response, ChatResponse previousChatResponse) {
		Assert.notNull(response, "HermesApi.ChatResponse must not be null");
		DefaultUsage newUsage = getDefaultUsage(response);
		DefaultUsage aggregatedUsage = getAggregatedUsage(previousChatResponse, newUsage);
		String finishReason = null;
		if (response.choices() != null && !response.choices().isEmpty()) {
			finishReason = response.choices().get(0).finishReason();
		}
		return ChatResponseMetadata.builder().usage(aggregatedUsage).model(response.model())
			.keyValue("finish_reason", finishReason).keyValue("id", response.id()).keyValue("created", response.created()).build();
	}

	@NonNull
	private static DefaultUsage getAggregatedUsage(ChatResponse previousChatResponse, DefaultUsage newUsage) {
		Integer promptTokens = newUsage.getPromptTokens();
		Integer generationTokens = newUsage.getCompletionTokens();
		int totalTokens = newUsage.getTotalTokens();
		if (previousChatResponse != null && previousChatResponse.getMetadata().getUsage() != null) {
			promptTokens += previousChatResponse.getMetadata().getUsage().getPromptTokens();
			generationTokens += previousChatResponse.getMetadata().getUsage().getCompletionTokens();
			totalTokens += previousChatResponse.getMetadata().getUsage().getTotalTokens();
		}
        return new DefaultUsage(promptTokens, generationTokens, totalTokens);
	}

	private static DefaultUsage getDefaultUsage(HermesApi.ChatResponse response) {
		if (response.usage() != null) {
			return new DefaultUsage(Optional.ofNullable(response.usage().promptTokens()).orElse(0),
				Optional.ofNullable(response.usage().completionTokens()).orElse(0));
		}
		return new DefaultUsage(0, 0);
	}

	@Override
	/**
	 * 同步执行一次聊天调用，并按需递归执行工具调用。
	 *
	 * @param prompt Spring AI 提示及本次运行选项
	 * @return 最终聊天响应；需要工具且不直接返回时为后续模型轮次的响应
	 * @throws IllegalArgumentException 模型为空、工具配置无效或存在不支持的消息类型时抛出
	 */
	public ChatResponse call(Prompt prompt) {
		return internalCall(buildRequestPrompt(prompt), null);
	}

	/**
	 * 异步执行一次聊天调用，并在受控弹性线程池上按需执行阻塞式工具。
	 *
	 * <p>每次订阅创建独立观测并保留 Reactor 上下文。工具结果要求继续对话时，使用
	 * 历史消息递归调用本方法的内部实现；直接返回工具结果时不再请求 Hermes 服务。</p>
	 *
	 * @param prompt Spring AI 提示及本次运行选项
	 * @return 发布最终聊天响应的单值序列
	 * @throws IllegalArgumentException 模型为空或工具配置无效时在创建发布者前抛出
	 */
	public Mono<ChatResponse> callAsync(Prompt prompt) {
		return internalCallAsync(buildRequestPrompt(prompt), null);
	}

	private Mono<ChatResponse> internalCallAsync(Prompt prompt, ChatResponse previousChatResponse) {
		return Mono.deferContextual(contextView -> {
			HermesApi.ChatRequest request = hermesChatRequest(prompt, false);
			Map<String, String> headers = hermesHttpHeaders(prompt);
			ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt).provider(HermesApiConstants.PROVIDER_NAME).build();
			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
				this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
				() -> observationContext, this.observationRegistry);
			observation.parentObservation(contextView.getOrDefault(
				ObservationThreadLocalAccessor.KEY, null)).start();

			return this.chatApi.chatAsync(request, headers)
				.map(apiResponse -> toChatResponse(apiResponse, previousChatResponse))
				.doOnNext(observationContext::setResponse)
				.flatMap(response -> {
					if (!this.toolExecutionEligibilityPredicate
							.isToolExecutionRequired(prompt.getOptions(), response)) {
						return Mono.just(response);
					}
					return Mono.fromCallable(() -> {
						try {
							ToolCallReactiveContextHolder.setContext(contextView);
							return this.toolCallingManager.executeToolCalls(prompt, response);
						}
						finally {
							ToolCallReactiveContextHolder.clearContext();
						}
					})
						.subscribeOn(Schedulers.boundedElastic())
						.flatMap(result -> {
							if (result.returnDirect()) {
								return Mono.just(ChatResponse.builder().from(response)
									.generations(ToolExecutionResult.buildGenerations(result)).build());
							}
							return internalCallAsync(new Prompt(result.conversationHistory(),
									prompt.getOptions()), response);
						});
				})
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
		});
	}

	private ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {
		HermesApi.ChatRequest request = hermesChatRequest(prompt, false);
		Map<String, String> headers = hermesHttpHeaders(prompt);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt).provider(HermesApiConstants.PROVIDER_NAME).build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
					() -> observationContext, this.observationRegistry)
			.observe(() -> {
				HermesApi.ChatResponse apiResp = this.retryTemplate.execute(ctx -> this.chatApi.chat(request, headers));
				List<AssistantMessage.ToolCall> toolCalls = HermesApiHelper.getToolCalls(apiResp).stream()
					.map(tc -> new AssistantMessage.ToolCall(tc.id(),
						tc.type() != null ? tc.type() : "function", tc.function().name(), tc.function().arguments()))
					.toList();
				var assistantMessage = AssistantMessage.builder()
					.content(HermesApiHelper.getContent(apiResp)).properties(Map.of()).toolCalls(toolCalls).build();
				String finishReason = null;
				if (apiResp.choices() != null && !apiResp.choices().isEmpty()) {
					finishReason = apiResp.choices().get(0).finishReason();
				}
				var genMeta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
				var generator = new Generation(assistantMessage, genMeta);
				ChatResponse cr = new ChatResponse(List.of(generator), from(apiResp, previousChatResponse));
				observationContext.setResponse(cr);
				return cr;
			});

		if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
			var result = this.toolCallingManager.executeToolCalls(prompt, response);
			if (result.returnDirect()) {
				return ChatResponse.builder().from(response).generations(ToolExecutionResult.buildGenerations(result)).build();
			}
			return internalCall(new Prompt(result.conversationHistory(), prompt.getOptions()), response);
		}
		return response;
	}

	private ChatResponse toChatResponse(HermesApi.ChatResponse apiResponse,
			ChatResponse previousChatResponse) {
		List<AssistantMessage.ToolCall> toolCalls = HermesApiHelper.getToolCalls(apiResponse).stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
				toolCall.type() != null ? toolCall.type() : "function",
				toolCall.function().name(), toolCall.function().arguments()))
			.toList();
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(HermesApiHelper.getContent(apiResponse))
			.properties(Map.of())
			.toolCalls(toolCalls)
			.build();
		String finishReason = null;
		if (apiResponse.choices() != null && !apiResponse.choices().isEmpty()) {
			finishReason = apiResponse.choices().get(0).finishReason();
		}
		Generation generation = new Generation(assistantMessage,
			ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation), from(apiResponse, previousChatResponse));
	}

	@Override
	/**
	 * 流式执行聊天调用，并按需执行工具调用及后续流式轮次。
	 *
	 * @param prompt Spring AI 提示及本次运行选项
	 * @return 经消息聚合器处理的聊天响应流
	 * @throws IllegalArgumentException 模型为空或工具配置无效时抛出
	 */
	public Flux<ChatResponse> stream(Prompt prompt) {
		return internalStream(buildRequestPrompt(prompt), null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			HermesApi.ChatRequest request = hermesChatRequest(prompt, true);
			Map<String, String> headers = hermesHttpHeaders(prompt);

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt).provider(HermesApiConstants.PROVIDER_NAME).build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
				this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext, this.observationRegistry);
			observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<HermesApi.ChatResponse> apiFlux = this.chatApi.streamingChat(request, headers);

			Flux<ChatResponse> chatResponse = apiFlux.map(chunk -> {
				String content = HermesApiHelper.getContent(chunk);
			List<AssistantMessage.ToolCall> toolCalls = HermesApiHelper.getToolCalls(chunk).stream()
					.map(tc -> new AssistantMessage.ToolCall(tc.id(),
						tc.type() != null ? tc.type() : "function", tc.function().name(), tc.function().arguments()))
					.toList();
				var msg = AssistantMessage.builder().content(content).properties(Map.of()).toolCalls(toolCalls).build();
				String finishReason = null;
				if (chunk.choices() != null && !chunk.choices().isEmpty()) {
					finishReason = chunk.choices().get(0).finishReason();
				}
				var genMeta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
				return new ChatResponse(List.of(new Generation(msg, genMeta)), from(chunk, previousChatResponse));
			});

			Flux<ChatResponse> chatResponseFlux = chatResponse.concatMap(response -> {
				if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
					return Flux.deferContextual(ctx -> {
						ToolExecutionResult toolExecutionResult;
						try { ToolCallReactiveContextHolder.setContext(ctx); toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response); }
						finally { ToolCallReactiveContextHolder.clearContext(); }
						if (toolExecutionResult.returnDirect()) {
							return Flux.just(ChatResponse.builder().from(response).generations(ToolExecutionResult.buildGenerations(toolExecutionResult)).build());
						}
						return this.internalStream(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()), response);
					}).subscribeOn(Schedulers.boundedElastic());
				}
				return Flux.just(response);
			}).doOnError(observation::error).doFinally(s -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

			return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
		});
	}

	/**
	 * 将运行时选项复制为 Hermes 选项并与默认配置合并。
	 *
	 * @param prompt 原始提示
	 * @return 指令不变、选项已归一化的请求提示
	 * @throws IllegalArgumentException 合并后模型为空或工具回调配置无效时抛出
	 */
	Prompt buildRequestPrompt(Prompt prompt) {
		HermesChatOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof HermesChatOptions ho) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(HermesChatOptions.fromOptions(ho), HermesChatOptions.class, HermesChatOptions.class);
			} else if (prompt.getOptions() instanceof ToolCallingChatOptions tc) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(tc, ToolCallingChatOptions.class, HermesChatOptions.class);
			} else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class, HermesChatOptions.class);
			}
		}
		HermesChatOptions requestOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions, HermesChatOptions.class);
		if (runtimeOptions != null) {
			requestOptions.setInternalToolExecutionEnabled(ModelOptionsUtils.mergeOption(runtimeOptions.getInternalToolExecutionEnabled(), this.defaultOptions.getInternalToolExecutionEnabled()));
			requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(runtimeOptions.getToolNames(), this.defaultOptions.getToolNames()));
			requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(), this.defaultOptions.getToolCallbacks()));
			requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(
				runtimeOptions.getToolContext() != null ? runtimeOptions.getToolContext() : Map.of(),
				this.defaultOptions.getToolContext() != null ? this.defaultOptions.getToolContext() : Map.of()));
		} else {
			requestOptions.setInternalToolExecutionEnabled(this.defaultOptions.getInternalToolExecutionEnabled());
			requestOptions.setToolNames(this.defaultOptions.getToolNames());
			requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
			requestOptions.setToolContext(this.defaultOptions.getToolContext());
		}
		if (!StringUtils.hasText(requestOptions.getModel())) throw new IllegalArgumentException("model cannot be null or empty");
		ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());
		return new Prompt(prompt.getInstructions(), requestOptions);
	}

	/**
	 * 将 Spring AI 消息和工具定义转换为 Hermes 聊天补全请求。
	 *
	 * @param prompt 已归一化为 Hermes 选项的提示
	 * @param stream 是否请求 SSE 流式响应
	 * @return Hermes 聊天补全请求
	 * @throws IllegalArgumentException 遇到不支持的消息类型时抛出
	 */
	HermesApi.ChatRequest hermesChatRequest(Prompt prompt, boolean stream) {
		List<HermesApi.Message> messages = prompt.getInstructions().stream().flatMap(msg -> {
			if (msg.getMessageType() == MessageType.SYSTEM) {
				return List.of(HermesApi.Message.builder(Role.SYSTEM).content(msg.getText()).build()).stream();
			} else if (msg.getMessageType() == MessageType.USER) {
				return List.of(HermesApi.Message.builder(Role.USER).content(msg.getText()).build()).stream();
			} else if (msg.getMessageType() == MessageType.ASSISTANT) {
				var am = (AssistantMessage) msg;
				List<HermesApi.Message.ToolCall> tcs = null;
				if (!CollectionUtils.isEmpty(am.getToolCalls())) {
					tcs = am.getToolCalls().stream().map(tc -> {
						var fn = new HermesApi.Message.ToolCallFunction(tc.name(), tc.arguments());
						return new HermesApi.Message.ToolCall(tc.id(), "function", fn);
					}).toList();
				}
				return List.of(HermesApi.Message.builder(Role.ASSISTANT).content(am.getText()).toolCalls(tcs).build()).stream();
			} else if (msg.getMessageType() == MessageType.TOOL) {
				var tm = (ToolResponseMessage) msg;
				return tm.getResponses().stream().map(tr ->
					HermesApi.Message.builder(Role.TOOL).content(tr.responseData()).toolCallId(tr.id()).name(tr.name()).build());
			}
			throw new IllegalArgumentException("Unsupported message type: " + msg.getMessageType());
		}).toList();

		HermesChatOptions requestOptions;
		if (prompt.getOptions() instanceof HermesChatOptions ho) {
			requestOptions = ho;
		} else if (prompt.getOptions() != null) {
			requestOptions = HermesChatOptions.fromOptions(HermesChatOptions.builder().build());
			if (prompt.getOptions().getTemperature() != null) requestOptions.setTemperature(prompt.getOptions().getTemperature());
			if (prompt.getOptions().getTopP() != null) requestOptions.setTopP(prompt.getOptions().getTopP());
			if (prompt.getOptions().getMaxTokens() != null) requestOptions.setMaxTokens(prompt.getOptions().getMaxTokens());
			if (prompt.getOptions().getStopSequences() != null) requestOptions.setStop(prompt.getOptions().getStopSequences());
			requestOptions.setModel(prompt.getOptions().getModel() != null ? prompt.getOptions().getModel() : this.defaultOptions.getModel());
		} else {
			requestOptions = this.defaultOptions;
		}

		HermesApi.ChatRequest.Builder b = HermesApi.ChatRequest.builder(requestOptions.getModel()).stream(stream)
			.messages(messages).temperature(requestOptions.getTemperature()).topP(requestOptions.getTopP())
			.frequencyPenalty(requestOptions.getFrequencyPenalty()).presencePenalty(requestOptions.getPresencePenalty())
			.seed(requestOptions.getSeed());

		if (requestOptions.getThinking() != null) b.thinking(requestOptions.getThinking());
		if (requestOptions.getMaxTokens() != null) b.maxCompletionTokens(requestOptions.getMaxTokens());
		if (requestOptions.getStop() != null && !requestOptions.getStop().isEmpty()) b.stop(requestOptions.getStop());
		if (requestOptions.getUser() != null) b.user(requestOptions.getUser());

		List<ToolDefinition> toolDefs = this.toolCallingManager.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefs)) {
			b.tools(toolDefs.stream().map(td -> {
				var fn = new ChatRequest.Tool.Function(td.name(), td.description(), ModelOptionsUtils.jsonToMap(td.inputSchema()));
				return new ChatRequest.Tool(fn);
			}).toList());
		}
		return b.build();
	}

	private Map<String, String> hermesHttpHeaders(Prompt prompt) {
		if (prompt.getOptions() instanceof HermesChatOptions ho) return ho.toHttpHeaders();
		return Map.of();
	}

	@Override
	/**
	 * 返回默认聊天选项的副本。
	 *
	 * @return 与内部默认值等价但可由调用方独立修改的 Hermes 选项
	 */
	public ChatOptions getDefaultOptions() { return HermesChatOptions.fromOptions(this.defaultOptions); }

	/**
	 * 替换聊天模型观测命名约定。
	 *
	 * @param c 新的观测约定
	 * @throws IllegalArgumentException 参数为 {@code null} 时抛出
	 */
	public void setObservationConvention(ChatModelObservationConvention c) {
		Assert.notNull(c, "observationConvention cannot be null"); this.observationConvention = c;
	}

	/**
	 * Hermes 聊天模型构建器。
	 *
	 * <p>收集 API、默认选项、工具调用、观测和重试依赖；未显式设置工具管理器时，
	 * 使用类级默认实现。</p>
	 */
	public static final class Builder {
		/** 待注入的 Hermes API 客户端。 */
		private HermesApi api;

		/** 默认聊天选项，初始模型为 {@code hermes-agent}。 */
		private HermesChatOptions defaultOptions = HermesChatOptions.builder().model(HermesModel.HERMES_AGENT.id()).build();

		/** 可选工具调用管理器。 */
		private ToolCallingManager toolCallingManager;

		/** 工具执行资格判断器。 */
		private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate = new DefaultToolExecutionEligibilityPredicate();

		/** Micrometer 观测注册表，默认不记录观测。 */
		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		/** 同步调用重试模板。 */
		private RetryTemplate retryTemplate = DEFAULT_RETRY_TEMPLATE;

		private Builder() {}
		/**
		 * 设置 Hermes API 客户端。
		 *
		 * @param v API 客户端
		 * @return 当前构建器
		 */
		public Builder api(HermesApi v) { api = v; return this; }
		/**
		 * 设置默认聊天选项。
		 *
		 * @param v 默认选项
		 * @return 当前构建器
		 */
		public Builder defaultOptions(HermesChatOptions v) { defaultOptions = v; return this; }
		/**
		 * 设置工具调用管理器。
		 *
		 * @param v 工具调用管理器；未设置时使用默认实现
		 * @return 当前构建器
		 */
		public Builder toolCallingManager(ToolCallingManager v) { toolCallingManager = v; return this; }
		/**
		 * 设置工具执行资格判断器。
		 *
		 * @param v 工具执行资格判断器
		 * @return 当前构建器
		 */
		public Builder toolExecutionEligibilityPredicate(ToolExecutionEligibilityPredicate v) { toolExecutionEligibilityPredicate = v; return this; }
		/**
		 * 设置 Micrometer 观测注册表。
		 *
		 * @param v 观测注册表
		 * @return 当前构建器
		 */
		public Builder observationRegistry(ObservationRegistry v) { observationRegistry = v; return this; }
		/**
		 * 设置同步调用重试模板。
		 *
		 * @param v 重试模板
		 * @return 当前构建器
		 */
		public Builder retryTemplate(RetryTemplate v) { retryTemplate = v; return this; }

		/**
		 * 创建 Hermes 聊天模型。
		 *
		 * @return 使用当前配置创建的聊天模型
		 * @throws IllegalArgumentException API、默认选项、观测、资格判断器或重试模板为空时抛出
		 */
		public HermesChatModel build() {
			if (toolCallingManager != null) {
				return new HermesChatModel(api, defaultOptions, toolCallingManager, observationRegistry, toolExecutionEligibilityPredicate, retryTemplate);
			}
			return new HermesChatModel(api, defaultOptions, DEFAULT_TOOL_CALLING_MANAGER, observationRegistry, toolExecutionEligibilityPredicate, retryTemplate);
		}
	}
}
