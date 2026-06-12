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
 * Java Client for the Hermes API Server.
 * <p>
 * Hermes exposes an OpenAI-compatible HTTP endpoint at {@code /v1/chat/completions},
 * {@code /v1/responses}, {@code /v1/models}, plus the Runs API, health, capabilities,
 * skills, and toolsets discovery.
 *
 * @author Loong Wan
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
public final class HermesApi {

	public static Builder builder() { return new Builder(); }

	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	private static final Logger logger = LoggerFactory.getLogger(HermesApi.class);

	private final RestClient restClient;
	private final WebClient webClient;
	private final SseErrorHandler sseErrorHandler;

	// spotless:off
	private HermesApi(String baseUrl, RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder,
			ResponseErrorHandler responseErrorHandler, SseErrorHandler sseErrorHandler) {
		this.restClient = restClientBuilder.clone().baseUrl(baseUrl)
			.defaultHeaders(h -> { h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON)); })
			.defaultStatusHandler(responseErrorHandler).build();
		this.webClient = webClientBuilder.clone().baseUrl(baseUrl)
			.defaultHeaders(h -> { h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)); })
			.build();
		this.sseErrorHandler = sseErrorHandler;
	}
	// spotless:on

	// ========================================================================
	// Chat Completions
	// ========================================================================

	public ChatResponse chat(ChatRequest chatRequest) { return chat(chatRequest, Map.of()); }

	public ChatResponse chat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");
		var spec = this.restClient.post().uri("/v1/chat/completions");
		extraHeaders.forEach(spec::header);
		return spec.body(chatRequest).retrieve().body(ChatResponse.class);
	}

	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest) { return streamingChat(chatRequest, Map.of()); }

	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(chatRequest.stream(), "Request must set stream to true.");
		var spec = this.webClient.post().uri("/v1/chat/completions").accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(spec::header);
		return spec.body(Mono.just(chatRequest), ChatRequest.class).retrieve()
			.bodyToFlux(ChatResponse.class).onErrorResume(sseErrorHandler::handle)
			.handle((chunk, sink) -> { if (chunk.choices() != null && !chunk.choices().isEmpty()) sink.next(chunk); });
	}

	// ========================================================================
	// Responses API
	// ========================================================================

	public Response responses(ResponseRequest request) { return responses(request, Map.of()); }

	public Response responses(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		var spec = this.restClient.post().uri("/v1/responses");
		extraHeaders.forEach(spec::header);
		return spec.body(request).retrieve().body(Response.class);
	}

	public Response getResponse(String responseId) {
		Assert.hasText(responseId, "responseId must not be empty");
		return this.restClient.get().uri("/v1/responses/{id}", responseId).retrieve().body(Response.class);
	}

	public void deleteResponse(String responseId) {
		Assert.hasText(responseId, "responseId must not be empty");
		this.restClient.delete().uri("/v1/responses/{id}", responseId).retrieve().toBodilessEntity();
	}

	// ========================================================================
	// Models
	// ========================================================================

	public ListModelResponse listModels() {
		return this.restClient.get().uri("/v1/models").retrieve().body(ListModelResponse.class);
	}

	public ModelResponse getModel(String modelId) {
		Assert.hasText(modelId, "modelId must not be empty");
		return this.restClient.get().uri("/v1/models/{id}", modelId).retrieve().body(ModelResponse.class);
	}

	// ========================================================================
	// Health
	// ========================================================================

	public Map<String, Object> health() {
		return this.restClient.get().uri("/health").retrieve().body(Map.class);
	}

	public Map<String, Object> healthV1() {
		return this.restClient.get().uri("/v1/health").retrieve().body(Map.class);
	}

	public Map<String, Object> healthDetailed() {
		return this.restClient.get().uri("/health/detailed").retrieve().body(Map.class);
	}

	// ========================================================================
	// Capabilities, Skills, Toolsets
	// ========================================================================

	public Capabilities getCapabilities() {
		return this.restClient.get().uri("/v1/capabilities").retrieve().body(Capabilities.class);
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> listSkills() {
		return (List<Map<String, Object>>) (List<?>) this.restClient.get().uri("/v1/skills").retrieve().body(List.class);
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> listToolsets() {
		return (List<Map<String, Object>>) (List<?>) this.restClient.get().uri("/v1/toolsets").retrieve().body(List.class);
	}

	// ========================================================================
	// Runs API
	// ========================================================================

	public Run createRun(RunRequest request) { return createRun(request, Map.of()); }

	public Run createRun(RunRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		var spec = this.restClient.post().uri("/v1/runs");
		extraHeaders.forEach(spec::header);
		return spec.body(request).retrieve().body(Run.class);
	}

	public Run getRun(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		return this.restClient.get().uri("/v1/runs/{id}", runId).retrieve().body(Run.class);
	}

	@SuppressWarnings("unchecked")
	public Flux<Map<String, Object>> streamRunEvents(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		return this.webClient.get().uri("/v1/runs/{id}/events", runId)
			.accept(MediaType.TEXT_EVENT_STREAM).retrieve().bodyToFlux(Map.class)
			.map(m -> (Map<String, Object>) m);
	}

	public void stopRun(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		this.restClient.post().uri("/v1/runs/{id}/stop", runId).retrieve().toBodilessEntity();
	}

	public void approveRun(String runId, Map<String, Object> decision) {
		Assert.hasText(runId, "runId must not be empty");
		this.restClient.post().uri("/v1/runs/{id}/approval", runId).body(decision).retrieve().toBodilessEntity();
	}

	// ========================================================================
	// Request / Response Models — Chat Completions
	// ========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatRequest(@JsonProperty("model") String model, @JsonProperty("messages") List<Message> messages,
			@JsonProperty("stream") Boolean stream, @JsonProperty("tools") List<Tool> tools,
			@JsonProperty("tool_choice") Object toolChoice, @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
			@JsonProperty("max_tokens") Integer maxTokens, @JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP, @JsonProperty("frequency_penalty") Double frequencyPenalty,
			@JsonProperty("presence_penalty") Double presencePenalty, @JsonProperty("seed") Integer seed,
			@JsonProperty("stop") Object stop, @JsonProperty("user") String user,
			@JsonProperty("stream_options") StreamOptions streamOptions) {

		public static Builder builder(String model) { return new Builder(model); }

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Tool(@JsonProperty("type") Type type, @JsonProperty("function") Function function) {
			public Tool(Function f) { this(Type.FUNCTION, f); }
			public enum Type { @JsonProperty("function") FUNCTION }
			public record Function(@JsonProperty("name") String name, @JsonProperty("description") String description,
					@JsonProperty("parameters") Map<String, Object> parameters) {}
		}

		public static final class Builder {
			private final String model; private List<Message> messages = List.of(); private boolean stream;
			private List<Tool> tools = List.of(); private Object toolChoice; private Integer maxCompletionTokens, maxTokens;
			private Double temperature, topP, frequencyPenalty, presencePenalty; private Integer seed;
			private Object stop; private String user; private StreamOptions streamOptions;

			public Builder(String m) { Assert.notNull(m, "model must not be null"); this.model = m; }
			public Builder messages(List<Message> v) { messages = v; return this; }
			public Builder stream(boolean v) { stream = v; return this; }
			public Builder tools(List<Tool> v) { tools = v; return this; }
			public Builder toolChoice(Object v) { toolChoice = v; return this; }
			public Builder maxCompletionTokens(Integer v) { maxCompletionTokens = v; return this; }
			public Builder maxTokens(Integer v) { maxTokens = v; return this; }
			public Builder temperature(Double v) { temperature = v; return this; }
			public Builder topP(Double v) { topP = v; return this; }
			public Builder frequencyPenalty(Double v) { frequencyPenalty = v; return this; }
			public Builder presencePenalty(Double v) { presencePenalty = v; return this; }
			public Builder seed(Integer v) { seed = v; return this; }
			public Builder stop(Object v) { stop = v; return this; }
			public Builder user(String v) { user = v; return this; }
			public Builder streamOptions(StreamOptions v) { streamOptions = v; return this; }
			public ChatRequest build() { return new ChatRequest(model, messages, stream, tools, toolChoice, maxCompletionTokens,
				maxTokens, temperature, topP, frequencyPenalty, presencePenalty, seed, stop, user, streamOptions); }
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(@JsonProperty("role") Role role, @JsonProperty("content") Object content,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls, @JsonProperty("tool_call_id") String toolCallId,
			@JsonProperty("name") String name) {

		public static Builder builder(Role role) { return new Builder(role); }

		public enum Role {
			@JsonProperty("system") SYSTEM, @JsonProperty("user") USER,
			@JsonProperty("assistant") ASSISTANT, @JsonProperty("tool") TOOL
		}

		/** Content part for array-format user messages (inline images). */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ContentPart(@JsonProperty("type") String type, @JsonProperty("text") String text,
				@JsonProperty("image_url") ImageUrl imageUrl) {}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ImageUrl(@JsonProperty("url") String url, @JsonProperty("detail") String detail) {}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCall(@JsonProperty("id") String id, @JsonProperty("type") String type,
				@JsonProperty("function") ToolCallFunction function) {}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCallFunction(@JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {}

		public static final class Builder {
			private final Role role; private Object content; private List<ToolCall> toolCalls;
			private String toolCallId, name;

			public Builder(Role r) { role = r; }
			public Builder content(String v) { content = v; return this; }
			public Builder content(List<ContentPart> v) { content = v; return this; }
			public Builder toolCalls(List<ToolCall> v) { toolCalls = v; return this; }
			public Builder toolCallId(String v) { toolCallId = v; return this; }
			public Builder name(String v) { name = v; return this; }
			public Message build() { return new Message(role, content, toolCalls, toolCallId, name); }
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponse(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("model") String model,
			@JsonProperty("choices") List<Choice> choices, @JsonProperty("usage") Usage usage) {

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Choice(@JsonProperty("index") Integer index, @JsonProperty("message") Message message,
				@JsonProperty("delta") Message delta, @JsonProperty("finish_reason") String finishReason) {}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
				@JsonProperty("completion_tokens") Integer completionTokens,
				@JsonProperty("total_tokens") Integer totalTokens,
				@JsonProperty("prompt_tokens_details") TokenDetails promptTokensDetails,
				@JsonProperty("completion_tokens_details") TokenDetails completionTokensDetails) {

			@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
			public record TokenDetails(@JsonProperty("cached_tokens") Integer cachedTokens,
					@JsonProperty("reasoning_tokens") Integer reasoningTokens) {}
		}
	}

	// ========================================================================
	// Models — Responses API
	// ========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseRequest(
			@JsonProperty("model") String model, @JsonProperty("input") Object input,
			@JsonProperty("instructions") String instructions,
			@JsonProperty("previous_response_id") String previousResponseId,
			@JsonProperty("conversation") String conversation, @JsonProperty("store") Boolean store) {}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Response(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("status") String status, @JsonProperty("model") String model,
			@JsonProperty("output") List<Map<String, Object>> output, @JsonProperty("usage") ChatResponse.Usage usage) {}

	// ========================================================================
	// Models — Models listing
	// ========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ListModelResponse(@JsonProperty("object") String object, @JsonProperty("data") List<ModelData> data) {}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelData(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission) {}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelResponse(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission) {}

	// ========================================================================
	// Models — Capabilities
	// ========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Capabilities(@JsonProperty("object") String object, @JsonProperty("platform") String platform,
			@JsonProperty("model") String model, @JsonProperty("auth") Map<String, Object> auth,
			@JsonProperty("features") Map<String, Boolean> features,
			@JsonProperty("session_key_header") String sessionKeyHeader) {}

	// ========================================================================
	// Models — Runs API
	// ========================================================================

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record RunRequest(@JsonProperty("input") String input, @JsonProperty("model") String model,
			@JsonProperty("session_id") String sessionId, @JsonProperty("instructions") String instructions,
			@JsonProperty("previous_response_id") String previousResponseId,
			@JsonProperty("conversation_history") List<Message> conversationHistory) {}

	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Run(@JsonProperty("object") String object, @JsonProperty("run_id") String runId,
			@JsonProperty("status") String status, @JsonProperty("session_id") String sessionId,
			@JsonProperty("model") String model, @JsonProperty("output") String output,
			@JsonProperty("usage") ChatResponse.Usage usage) {}

	// ========================================================================
	// Builder
	// ========================================================================

	public static final class Builder {
		private String baseUrl = HermesApiConstants.DEFAULT_BASE_URL;
		private RestClient.Builder restClientBuilder = RestClient.builder();
		private WebClient.Builder webClientBuilder = WebClient.builder();
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;
		private SseErrorHandler sseErrorHandler = SseErrorHandler.DEFAULT;

		public Builder baseUrl(String v) { Assert.hasText(v, "baseUrl must not be empty"); baseUrl = v; return this; }
		public Builder restClientBuilder(RestClient.Builder v) { Assert.notNull(v, "restClientBuilder must not be null"); restClientBuilder = v; return this; }
		public Builder webClientBuilder(WebClient.Builder v) { Assert.notNull(v, "webClientBuilder must not be null"); webClientBuilder = v; return this; }
		public Builder responseErrorHandler(ResponseErrorHandler v) { Assert.notNull(v, "responseErrorHandler must not be null"); responseErrorHandler = v; return this; }
		public Builder sseErrorHandler(SseErrorHandler v) { Assert.notNull(v, "sseErrorHandler must not be null"); sseErrorHandler = v; return this; }
		public HermesApi build() { return new HermesApi(baseUrl, restClientBuilder, webClientBuilder, responseErrorHandler, sseErrorHandler); }
	}
}
