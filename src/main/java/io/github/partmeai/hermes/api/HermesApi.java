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

package io.github.partmeai.hermes.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Java Client for the Hermes Gateway OpenAI-compatible HTTP API.
 * <p>
 * Calls {@code /v1/chat/completions}, {@code /v1/models}, {@code /v1/embeddings},
 * and {@code /v1/responses} as described in the Gateway documentation.
 * <p>
 * The {@code model} field uses Hermes agent-target routing:
 * {@code hermes/default}, {@code hermes/<agentId>}.
 * Use HTTP headers ({@code x-hermes-model}, {@code x-hermes-session-key}, etc.)
 * to control backend model override, session routing, and channel context.
 *
 * @author Loong Wan
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api">Hermes OpenAI HTTP API</a>
 */
public final class HermesApi {

	public static Builder builder() {
		return new Builder();
	}

	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	private static final Logger logger = LoggerFactory.getLogger(HermesApi.class);

	private final RestClient restClient;

	private final WebClient webClient;

	private final SseErrorHandler sseErrorHandler;

	private HermesApi(String baseUrl, RestClient.Builder restClientBuilder,
			WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler,
			SseErrorHandler sseErrorHandler) {
		this.restClient = restClientBuilder
				.clone()
				.baseUrl(baseUrl)
				.defaultHeaders(headers -> {
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setAccept(List.of(MediaType.APPLICATION_JSON));
				})
				.defaultStatusHandler(responseErrorHandler)
				.build();

		this.webClient = webClientBuilder
				.clone()
				.baseUrl(baseUrl)
				.defaultHeaders(headers -> {
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
				})
				.build();

		this.sseErrorHandler = sseErrorHandler;
	}

	// --------------------------------------------------------------------------
	// Chat Completions
	// --------------------------------------------------------------------------

	public ChatResponse chat(ChatRequest chatRequest) {
		return chat(chatRequest, Map.of());
	}

	public ChatResponse chat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");

		var requestSpec = this.restClient.post().uri("/v1/chat/completions");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec.body(chatRequest).retrieve().body(ChatResponse.class);
	}

	/**
	 * Streaming (SSE) response for the chat completion request.
	 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api#streaming-sse">Hermes Streaming SSE</a>
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest) {
		return streamingChat(chatRequest, Map.of());
	}

	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(chatRequest.stream(), "Request must set the stream property to true.");

		var requestSpec = this.webClient.post()
				.uri("/v1/chat/completions")
				.accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(requestSpec::header);

		return requestSpec
				.body(Mono.just(chatRequest), ChatRequest.class)
				.retrieve()
				.bodyToFlux(ChatResponse.class)
				.onErrorResume(sseErrorHandler::handle)
				.handle((chunk, sink) -> {
					if (logger.isTraceEnabled()) {
						logger.trace("SSE chunk: {}", chunk);
					}
					if (chunk.choices() != null && !chunk.choices().isEmpty()) {
						sink.next(chunk);
					}
				});
	}

	// --------------------------------------------------------------------------
	// Responses
	// --------------------------------------------------------------------------

	public Map<String, Object> responses(Map<String, Object> responsesRequest) {
		return responses(responsesRequest, Map.of());
	}

	public Map<String, Object> responses(Map<String, Object> responsesRequest, Map<String, String> extraHeaders) {
		Assert.notNull(responsesRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post().uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec.body(responsesRequest).retrieve().body(Map.class);
	}

	// --------------------------------------------------------------------------
	// Models
	// --------------------------------------------------------------------------

	/**
	 * List agent targets available on the Hermes Gateway.
	 * Returns agent-target ids: {@code hermes}, {@code hermes/default},
	 * and {@code hermes/<agentId>} entries.
	 */
	public ListModelResponse listModels() {
		return this.restClient.get().uri("/v1/models").retrieve().body(ListModelResponse.class);
	}

	/**
	 * Show information about a specific agent target on the Hermes Gateway.
	 */
	public ModelResponse getModel(String modelId) {
		Assert.hasText(modelId, "modelId must not be empty");
		return this.restClient.get().uri("/v1/models/{id}", modelId).retrieve().body(ModelResponse.class);
	}

	// --------------------------------------------------------------------------
	// Embeddings
	// --------------------------------------------------------------------------

	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest) {
		return embed(embeddingsRequest, Map.of());
	}

	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest, Map<String, String> extraHeaders) {
		Assert.notNull(embeddingsRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post().uri("/v1/embeddings");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec.body(embeddingsRequest).retrieve().body(EmbeddingsResponse.class);
	}

	// ========================================================================
	// Request / Response Models — Chat Completions
	// ========================================================================

	/**
	 * OpenAI-compatible Chat Completions request body.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatRequest(
			@JsonProperty("model") String model,
			@JsonProperty("messages") List<Message> messages,
			@JsonProperty("stream") Boolean stream,
			@JsonProperty("tools") List<Tool> tools,
			@JsonProperty("tool_choice") Object toolChoice,
			@JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
			@JsonProperty("max_tokens") Integer maxTokens,
			@JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP,
			@JsonProperty("frequency_penalty") Double frequencyPenalty,
			@JsonProperty("presence_penalty") Double presencePenalty,
			@JsonProperty("seed") Integer seed,
			@JsonProperty("stop") Object stop,
			@JsonProperty("user") String user,
			@JsonProperty("stream_options") StreamOptions streamOptions
	) {

		public static Builder builder(String model) {
			return new Builder(model);
		}

		@JsonInclude(Include.NON_NULL)
		public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Tool(
				@JsonProperty("type") Type type,
				@JsonProperty("function") Function function) {

			public Tool(Function function) {
				this(Type.FUNCTION, function);
			}

			public enum Type {
				@JsonProperty("function") FUNCTION
			}

			public record Function(
					@JsonProperty("name") String name,
					@JsonProperty("description") String description,
					@JsonProperty("parameters") Map<String, Object> parameters) {
			}
		}

		public static final class Builder {

			private final String model;
			private List<Message> messages = List.of();
			private boolean stream = false;
			private List<Tool> tools = List.of();
			private Object toolChoice;
			private Integer maxCompletionTokens;
			private Integer maxTokens;
			private Double temperature;
			private Double topP;
			private Double frequencyPenalty;
			private Double presencePenalty;
			private Integer seed;
			private Object stop;
			private String user;
			private StreamOptions streamOptions;

			public Builder(String model) {
				Assert.notNull(model, "The model can not be null.");
				this.model = model;
			}

			public Builder messages(List<Message> messages) { this.messages = messages; return this; }
			public Builder stream(boolean stream) { this.stream = stream; return this; }
			public Builder tools(List<Tool> tools) { this.tools = tools; return this; }
			public Builder toolChoice(Object toolChoice) { this.toolChoice = toolChoice; return this; }
			public Builder maxCompletionTokens(Integer v) { this.maxCompletionTokens = v; return this; }
			public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
			public Builder temperature(Double v) { this.temperature = v; return this; }
			public Builder topP(Double v) { this.topP = v; return this; }
			public Builder frequencyPenalty(Double v) { this.frequencyPenalty = v; return this; }
			public Builder presencePenalty(Double v) { this.presencePenalty = v; return this; }
			public Builder seed(Integer v) { this.seed = v; return this; }
			public Builder stop(Object v) { this.stop = v; return this; }
			public Builder user(String v) { this.user = v; return this; }
			public Builder streamOptions(StreamOptions v) { this.streamOptions = v; return this; }

			public ChatRequest build() {
				return new ChatRequest(model, messages, stream, tools, toolChoice,
						maxCompletionTokens, maxTokens, temperature, topP,
						frequencyPenalty, presencePenalty, seed, stop, user, streamOptions);
			}
		}
	}

	/**
	 * A message in a chat conversation.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(
			@JsonProperty("role") Role role,
			@JsonProperty("content") String content,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls,
			@JsonProperty("tool_call_id") String toolCallId,
			@JsonProperty("name") String name
	) {

		public static Builder builder(Role role) { return new Builder(role); }

		public enum Role {
			@JsonProperty("system") SYSTEM,
			@JsonProperty("user") USER,
			@JsonProperty("assistant") ASSISTANT,
			@JsonProperty("tool") TOOL
		}

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCall(
				@JsonProperty("id") String id,
				@JsonProperty("type") String type,
				@JsonProperty("function") ToolCallFunction function
		) {}

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCallFunction(
				@JsonProperty("name") String name,
				@JsonProperty("arguments") String arguments
		) {}

		public static final class Builder {
			private final Role role;
			private String content;
			private List<ToolCall> toolCalls;
			private String toolCallId;
			private String name;

			public Builder(Role role) { this.role = role; }
			public Builder content(String v) { this.content = v; return this; }
			public Builder toolCalls(List<ToolCall> v) { this.toolCalls = v; return this; }
			public Builder toolCallId(String v) { this.toolCallId = v; return this; }
			public Builder name(String v) { this.name = v; return this; }
			public Message build() { return new Message(role, content, toolCalls, toolCallId, name); }
		}
	}

	/**
	 * OpenAI-compatible Chat Completions response.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponse(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("model") String model,
			@JsonProperty("choices") List<Choice> choices,
			@JsonProperty("usage") Usage usage
	) {

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Choice(
				@JsonProperty("index") Integer index,
				@JsonProperty("message") Message message,
				@JsonProperty("delta") Message delta,
				@JsonProperty("finish_reason") String finishReason
		) {}

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Usage(
				@JsonProperty("prompt_tokens") Integer promptTokens,
				@JsonProperty("completion_tokens") Integer completionTokens,
				@JsonProperty("total_tokens") Integer totalTokens,
				@JsonProperty("prompt_tokens_details") TokenDetails promptTokensDetails,
				@JsonProperty("completion_tokens_details") TokenDetails completionTokensDetails
		) {

			@JsonInclude(Include.NON_NULL)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public record TokenDetails(
					@JsonProperty("cached_tokens") Integer cachedTokens,
					@JsonProperty("reasoning_tokens") Integer reasoningTokens
			) {}
		}
	}

	// ========================================================================
	// Request / Response Models — Models
	// ========================================================================

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListModelResponse(
			@JsonProperty("object") String object,
			@JsonProperty("data") List<ModelData> data
	) {}

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelData(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission
	) {}

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelResponse(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission
	) {}

	// ========================================================================
	// Request / Response Models — Embeddings
	// ========================================================================

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingsRequest(
			@JsonProperty("model") String model,
			@JsonProperty("input") Object input,
			@JsonProperty("dimensions") Integer dimensions,
			@JsonProperty("user") String user
	) {
		public EmbeddingsRequest(String model, String input) {
			this(model, (Object) input, null, null);
		}

		public EmbeddingsRequest(String model, List<String> input) {
			this(model, (Object) input, null, null);
		}
	}

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingsResponse(
			@JsonProperty("object") String object,
			@JsonProperty("data") List<EmbeddingData> data,
			@JsonProperty("model") String model,
			@JsonProperty("usage") ChatResponse.Usage usage
	) {}

	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingData(
			@JsonProperty("object") String object,
			@JsonProperty("index") Integer index,
			@JsonProperty("embedding") List<Float> embedding
	) {}

	// ========================================================================
	// Builder
	// ========================================================================

	public static final class Builder {

		private String baseUrl = HermesApiConstants.DEFAULT_BASE_URL;
		private RestClient.Builder restClientBuilder = RestClient.builder();
		private WebClient.Builder webClientBuilder = WebClient.builder();
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;
		private SseErrorHandler sseErrorHandler = SseErrorHandler.DEFAULT;

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be null or empty");
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder cannot be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "webClientBuilder cannot be null");
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			Assert.notNull(responseErrorHandler, "responseErrorHandler cannot be null");
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		/**
		 * Configure SSE parse error handling for streaming chat completions.
		 * <p>
		 * Default: {@link SseErrorHandler#DEFAULT} — suppresses the {@code [DONE]}
		 * sentinel error, propagates everything else.
		 * <p>
		 * Use {@link SseErrorHandler#LENIENT} to suppress all parse errors, or
		 * {@link SseErrorHandler#STRICT} to never suppress.
		 */
		public Builder sseErrorHandler(SseErrorHandler sseErrorHandler) {
			Assert.notNull(sseErrorHandler, "sseErrorHandler cannot be null");
			this.sseErrorHandler = sseErrorHandler;
			return this;
		}

		public HermesApi build() {
			return new HermesApi(this.baseUrl, this.restClientBuilder,
					this.webClientBuilder, this.responseErrorHandler, this.sseErrorHandler);
		}
	}
}
