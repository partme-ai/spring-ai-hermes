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
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Hermes API Server Java 客户端。
 *
 * <p>封装 OpenAI 兼容的 Chat Completions、Responses、Models 接口，以及 Runs、
 * Sessions、Jobs、健康检查、能力、技能和工具集发现接口。所有 HTTP 请求统一受
 * {@link HermesRequestLimiter} 无等待并发门禁保护；聊天 SSE 以字符串读取并识别
 * {@code [DONE]}，随后合并跨数据块的工具调用碎片。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@Slf4j
public final class HermesApi {

	/** 默认共享并发请求上限。 */
	public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 512;

	/** Hermes 聊天 SSE 的结束标记。 */
	private static final String SSE_DONE = "[DONE]";

	/**
	 * 创建 Hermes API 客户端构建器。
	 *
	 * @return 带有默认连接、错误处理和并发上限的构建器
	 */
	public static Builder builder() { return new Builder(); }

	/** 请求体为空时使用的统一断言消息。 */
	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	/** 执行阻塞式 HTTP 请求的同步客户端。 */
	private final RestClient restClient;

	/** 执行异步和 SSE 请求的响应式客户端。 */
	private final WebClient webClient;

	/** 将聊天 SSE 解析异常转换为替代发布者的策略。 */
	private final SseErrorHandler sseErrorHandler;

	/** 已订阅且尚未终止的聊天 SSE 流数量。 */
	private final AtomicInteger activeStreams = new AtomicInteger();

	/** 所有 HTTP 请求共享的并发门禁。 */
	private final HermesRequestLimiter requestLimiter;

	/** 合并聊天 SSE 中跨块工具调用参数的聚合器。 */
	private final HermesStreamToolCallAggregator streamToolCallAggregator = new HermesStreamToolCallAggregator();

	// spotless:off
	private HermesApi(String baseUrl, RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder,
			ResponseErrorHandler responseErrorHandler, SseErrorHandler sseErrorHandler,
			int maxConcurrentRequests) {
		this.restClient = restClientBuilder.clone().baseUrl(baseUrl)
			.defaultHeaders(h -> { h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON)); })
			.defaultStatusHandler(responseErrorHandler).build();
		this.webClient = webClientBuilder.clone().baseUrl(baseUrl)
			.defaultHeaders(h -> { h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)); })
			.build();
		this.sseErrorHandler = sseErrorHandler;
		this.requestLimiter = new HermesRequestLimiter(maxConcurrentRequests);
		log.debug("Initialized Hermes API client: baseUrl={}, maxConcurrentRequests={}",
				baseUrl, maxConcurrentRequests);
	}
	// spotless:on

	// ========================================================================
	// Chat Completions
	// ========================================================================

	/**
	 * 同步执行非流式聊天补全请求。
	 *
	 * @param chatRequest 已关闭流模式的聊天请求
	 * @return Hermes 聊天补全响应
	 * @throws IllegalArgumentException 请求为空或启用了流模式时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public ChatResponse chat(ChatRequest chatRequest) { return chat(chatRequest, Map.of()); }

	/**
	 * 携带额外请求头同步执行非流式聊天补全请求。
	 *
	 * @param chatRequest 已关闭流模式的聊天请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return Hermes 聊天补全响应
	 * @throws IllegalArgumentException 请求为空或启用了流模式时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public ChatResponse chat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");
		var spec = this.restClient.post().uri(HermesApiConstants.V1_CHAT_COMPLETIONS);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.execute(() -> spec.body(chatRequest).retrieve().body(ChatResponse.class));
	}

	/**
	 * 异步执行非流式聊天补全请求。
	 *
	 * @param chatRequest 已关闭流模式的聊天请求
	 * @return 发布 Hermes 聊天补全响应的单值序列
	 * @throws IllegalArgumentException 请求为空或启用了流模式时抛出
	 */
	public Mono<ChatResponse> chatAsync(ChatRequest chatRequest) {
		return chatAsync(chatRequest, Map.of());
	}

	/**
	 * 携带额外请求头异步执行非流式聊天补全请求。
	 *
	 * @param chatRequest 已关闭流模式的聊天请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return 订阅时申请并发许可并发布聊天响应的单值序列
	 * @throws IllegalArgumentException 请求为空或启用了流模式时抛出
	 */
	public Mono<ChatResponse> chatAsync(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");
		var spec = this.webClient.post().uri(HermesApiConstants.V1_CHAT_COMPLETIONS);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.guard(spec.bodyValue(chatRequest).retrieve().bodyToMono(ChatResponse.class));
	}

	/**
	 * 执行 SSE 流式聊天补全请求。
	 *
	 * @param chatRequest 已启用流模式的聊天请求
	 * @return 过滤结束标记并聚合工具调用碎片后的响应流
	 * @throws IllegalArgumentException 请求为空或未启用流模式时抛出
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest) { return streamingChat(chatRequest, Map.of()); }

	/**
	 * 携带额外请求头执行 SSE 流式聊天补全请求。
	 *
	 * <p>响应体先以字符串读取，在 JSON 映射前识别 {@code [DONE]}。工具调用碎片按
	 * 窗口合并，解析异常交给配置的 {@link SseErrorHandler}，并发许可持续持有至流终止。</p>
	 *
	 * @param chatRequest 已启用流模式的聊天请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return 有候选项的聊天响应流
	 * @throws IllegalArgumentException 请求为空或未启用流模式时抛出
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(chatRequest.stream(), "Request must set stream to true.");
		var spec = this.webClient.post().uri(HermesApiConstants.V1_CHAT_COMPLETIONS).accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.guard(Flux.defer(() -> {
			int active = this.activeStreams.incrementAndGet();
			if (log.isTraceEnabled()) {
				log.trace("Opened Hermes chat stream: activeStreams={}", active);
			}
			Flux<ChatResponse> chunks = spec.bodyValue(chatRequest).retrieve()
				.bodyToFlux(String.class)
				.takeUntil(SSE_DONE::equals)
				.filter(data -> !SSE_DONE.equals(data))
				.map(data -> ModelOptionsUtils.<ChatResponse>jsonToObject(data, ChatResponse.class))
				.onErrorResume(this.sseErrorHandler::handle);
			return this.streamToolCallAggregator.aggregate(chunks)
				.filter(chunk -> chunk.choices() != null && !chunk.choices().isEmpty())
				.doFinally(signal -> {
					int remaining = this.activeStreams.decrementAndGet();
					if (log.isTraceEnabled()) {
						log.trace("Closed Hermes chat stream: signal={}, activeStreams={}",
								signal, remaining);
					}
				});
		}));
	}

	/** @return 当前已经打开且尚未终止的聊天 SSE 流数量 */
	public int getActiveStreamCount() {
		return this.activeStreams.get();
	}

	/** @return 当前占用并发许可的同步、异步及流式请求总数 */
	public int getInFlightRequestCount() {
		return this.requestLimiter.getInFlightRequestCount();
	}

	/** @return 客户端配置的最大并发请求数 */
	public int getMaxConcurrentRequests() {
		return this.requestLimiter.getMaxConcurrentRequests();
	}

	// ========================================================================
	// Responses API
	// ========================================================================

	/**
	 * 同步创建 Responses API 响应。
	 *
	 * @param request Responses API 请求
	 * @return 服务端响应
	 * @throws IllegalArgumentException 请求为 {@code null} 时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Response responses(ResponseRequest request) { return responses(request, Map.of()); }

	/**
	 * 携带额外请求头同步创建 Responses API 响应。
	 *
	 * @param request Responses API 请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return 服务端响应
	 * @throws IllegalArgumentException 请求为 {@code null} 时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Response responses(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		var spec = this.restClient.post().uri(HermesApiConstants.V1_RESPONSES);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.execute(() -> spec.body(request).retrieve().body(Response.class));
	}

	/**
	 * 携带额外请求头异步创建 Responses API 响应。
	 *
	 * @param request Responses API 请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return 发布服务端响应的单值序列
	 * @throws IllegalArgumentException 请求为 {@code null} 时抛出
	 */
	public Mono<Response> responsesAsync(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		var spec = this.webClient.post().uri(HermesApiConstants.V1_RESPONSES);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.guard(spec.bodyValue(request).retrieve().bodyToMono(Response.class));
	}

	/**
	 * 按标识获取已存储的 Responses API 响应。
	 *
	 * @param responseId 响应标识
	 * @return 已存储响应
	 * @throws IllegalArgumentException 标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Response getResponse(String responseId) {
		Assert.hasText(responseId, "responseId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_RESPONSES_BY_ID, responseId).retrieve().body(Response.class));
	}

	/**
	 * 删除已存储的 Responses API 响应。
	 *
	 * @param responseId 响应标识
	 * @return 服务端返回 2xx 状态时为 {@code true}
	 * @throws IllegalArgumentException 标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public boolean deleteResponse(String responseId) {
		Assert.hasText(responseId, "responseId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.delete()
			.uri(HermesApiConstants.V1_RESPONSES_BY_ID, responseId).retrieve()
			.toBodilessEntity().getStatusCode().is2xxSuccessful());
	}

	// ========================================================================
	// Models
	// ========================================================================

	/**
	 * 同步列出服务端公开的模型。
	 *
	 * @return 模型列表响应
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public ListModelResponse listModels() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_MODELS).retrieve().body(ListModelResponse.class));
	}

	/**
	 * 异步列出服务端公开的模型。
	 *
	 * @return 发布模型列表响应的单值序列
	 */
	public Mono<ListModelResponse> listModelsAsync() {
		return this.requestLimiter.guard(this.webClient.get().uri(HermesApiConstants.V1_MODELS)
			.retrieve().bodyToMono(ListModelResponse.class));
	}

	/**
	 * 按标识获取单个模型元数据。
	 *
	 * @param modelId 模型标识
	 * @return 单模型响应
	 * @throws IllegalArgumentException 模型标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public ModelResponse getModel(String modelId) {
		Assert.hasText(modelId, "modelId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_MODELS_BY_ID, modelId).retrieve().body(ModelResponse.class));
	}

	// ========================================================================
	// Health
	// ========================================================================

	/**
	 * 调用基础健康检查端点。
	 *
	 * @return 服务端健康信息映射
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> health() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.HEALTH).retrieve().body(Map.class));
	}

	/**
	 * 调用 OpenAI 兼容前缀下的健康检查端点。
	 *
	 * @return 服务端健康信息映射
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> healthV1() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_HEALTH).retrieve().body(Map.class));
	}

	/**
	 * 调用扩展健康检查端点。
	 *
	 * @return 包含扩展诊断信息的映射
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> healthDetailed() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.HEALTH_DETAILED).retrieve().body(Map.class));
	}

	// ========================================================================
	// Capabilities, Skills, Toolsets
	// ========================================================================

	/**
	 * 获取服务端能力和特性开关。
	 *
	 * @return Hermes 能力描述
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Capabilities getCapabilities() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_CAPABILITIES).retrieve().body(Capabilities.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 列出 Agent 技能元数据。
	 *
	 * @return 技能对象映射列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Map<String, Object>> listSkills() {
		return this.requestLimiter.execute(() -> (List<Map<String, Object>>) (List<?>) this.restClient
			.get().uri(HermesApiConstants.V1_SKILLS).retrieve().body(List.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 列出 Agent 工具集元数据。
	 *
	 * @return 工具集对象映射列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Map<String, Object>> listToolsets() {
		return this.requestLimiter.execute(() -> (List<Map<String, Object>>) (List<?>) this.restClient
			.get().uri(HermesApiConstants.V1_TOOLSETS).retrieve().body(List.class));
	}

	// ========================================================================
	// Runs API
	// ========================================================================

	/**
	 * 创建 Agent 运行。
	 *
	 * @param request 运行请求
	 * @return 新建运行的状态与结果
	 * @throws IllegalArgumentException 请求为 {@code null} 时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Run createRun(RunRequest request) { return createRun(request, Map.of()); }

	/**
	 * 携带额外请求头创建 Agent 运行。
	 *
	 * @param request 运行请求
	 * @param extraHeaders 附加到本次请求的 HTTP 请求头
	 * @return 新建运行的状态与结果
	 * @throws IllegalArgumentException 请求为 {@code null} 时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Run createRun(RunRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		var spec = this.restClient.post().uri(HermesApiConstants.V1_RUNS);
		extraHeaders.forEach(spec::header);
		return this.requestLimiter.execute(() -> spec.body(request).retrieve().body(Run.class));
	}

	/**
	 * 查询 Agent 运行状态或结果。
	 *
	 * @param runId 运行标识
	 * @return 运行状态与结果
	 * @throws IllegalArgumentException 运行标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Run getRun(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.V1_RUNS_BY_ID, runId).retrieve().body(Run.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 订阅 Agent 运行的 SSE 事件。
	 *
	 * @param runId 运行标识
	 * @return 运行事件映射流，许可持续持有至订阅终止
	 * @throws IllegalArgumentException 运行标识为空白时抛出
	 */
	public Flux<Map<String, Object>> streamRunEvents(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		return this.requestLimiter.guard(this.webClient.get().uri(HermesApiConstants.V1_RUNS_EVENTS, runId)
			.accept(MediaType.TEXT_EVENT_STREAM).retrieve().bodyToFlux(Map.class)
			.map(m -> (Map<String, Object>) m));
	}

	/**
	 * 请求服务端停止指定 Agent 运行。
	 *
	 * @param runId 运行标识
	 * @throws IllegalArgumentException 运行标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public void stopRun(String runId) {
		Assert.hasText(runId, "runId must not be empty");
		this.requestLimiter.execute(() -> {
			this.restClient.post().uri(HermesApiConstants.V1_RUNS_STOP, runId)
				.retrieve().toBodilessEntity();
			return null;
		});
	}

	/**
	 * 提交指定 Agent 运行的审批决策。
	 *
	 * @param runId 运行标识
	 * @param decision 审批决策请求体
	 * @throws IllegalArgumentException 运行标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public void approveRun(String runId, Map<String, Object> decision) {
		Assert.hasText(runId, "runId must not be empty");
		this.requestLimiter.execute(() -> {
			this.restClient.post().uri(HermesApiConstants.V1_RUNS_APPROVAL, runId)
				.body(decision).retrieve().toBodilessEntity();
			return null;
		});
	}

	// ========================================================================
	// Sessions API
	// ========================================================================

	@SuppressWarnings("unchecked")
	/**
	 * 列出全部会话。
	 *
	 * @return 会话列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Session> listSessions() {
		return this.requestLimiter.execute(() -> (List<Session>) (List<?>) this.restClient
			.get().uri(HermesApiConstants.API_SESSIONS).retrieve().body(List.class));
	}

	/**
	 * 按可选条件分页列出会话。
	 *
	 * @param limit 单页最大数量；为 {@code null} 时不发送该查询参数
	 * @param offset 起始偏移量；为 {@code null} 时不发送该查询参数
	 * @param source 会话来源过滤条件；为 {@code null} 时不发送该查询参数
	 * @param includeChildren 是否包含子会话；为 {@code null} 时不发送该查询参数
	 * @return 符合条件的会话列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Session> listSessions(Integer limit, Integer offset, String source, Boolean includeChildren) {
		return this.requestLimiter.execute(() -> {
			var uri = this.restClient.get().uri(b -> {
				var u = b.path(HermesApiConstants.API_SESSIONS);
				if (limit != null) u.queryParam("limit", limit);
				if (offset != null) u.queryParam("offset", offset);
				if (source != null) u.queryParam("source", source);
				if (includeChildren != null) u.queryParam("include_children", includeChildren);
				return u.build();
			});
			@SuppressWarnings("unchecked")
			List<Session> sessions = (List<Session>) (List<?>) uri.retrieve().body(List.class);
			return sessions;
		});
	}

	/**
	 * 创建会话。
	 *
	 * @param request 会话标题请求
	 * @return 新建会话
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Session createSession(SessionCreateRequest request) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_SESSIONS).body(request).retrieve().body(Session.class));
	}

	/**
	 * 按标识获取会话。
	 *
	 * @param sessionId 会话标识
	 * @return 会话数据
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Session getSession(String sessionId) {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.API_SESSIONS_BY_ID, sessionId).retrieve().body(Session.class));
	}

	/**
	 * 局部更新会话。
	 *
	 * @param sessionId 会话标识
	 * @param patch 待更新字段映射
	 * @return 更新后的会话数据
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Session updateSession(String sessionId, Map<String, Object> patch) {
		return this.requestLimiter.execute(() -> this.restClient.patch()
			.uri(HermesApiConstants.API_SESSIONS_BY_ID, sessionId).body(patch)
			.retrieve().body(Session.class));
	}

	/**
	 * 删除会话。
	 *
	 * @param sessionId 会话标识
	 * @return 服务端返回 2xx 状态时为 {@code true}
	 * @throws IllegalArgumentException 会话标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public boolean deleteSession(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.delete()
			.uri(HermesApiConstants.API_SESSIONS_BY_ID, sessionId).retrieve()
			.toBodilessEntity().getStatusCode().is2xxSuccessful());
	}

	@SuppressWarnings("unchecked")
	/**
	 * 获取指定会话的消息列表。
	 *
	 * @param sessionId 会话标识
	 * @return 消息对象映射列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Map<String, Object>> getSessionMessages(String sessionId) {
		return this.requestLimiter.execute(() -> (List<Map<String, Object>>) (List<?>) this.restClient.get()
			.uri(HermesApiConstants.API_SESSIONS_MESSAGES, sessionId).retrieve().body(List.class));
	}

	/**
	 * 从指定会话分叉出子会话。
	 *
	 * @param sessionId 父会话标识
	 * @param title 子会话标题；为 {@code null} 时发送空请求体
	 * @return 新建的子会话
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Session forkSession(String sessionId, String title) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_SESSIONS_FORK, sessionId)
			.body(title != null ? Map.of("title", title) : Map.of()).retrieve().body(Session.class));
	}

	/**
	 * 在指定会话中同步发送聊天输入。
	 *
	 * @param sessionId 会话标识
	 * @param input 用户输入
	 * @return 聊天响应
	 * @throws IllegalArgumentException 会话标识或输入为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public ChatResponse sessionChat(String sessionId, String input) {
		Assert.hasText(sessionId, "sessionId must not be empty");
		Assert.hasText(input, "input must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_SESSIONS_CHAT, sessionId)
			.body(Map.of("input", input)).retrieve().body(ChatResponse.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 在指定会话中发送聊天输入并订阅 SSE 事件。
	 *
	 * @param sessionId 会话标识
	 * @param input 用户输入
	 * @return 会话聊天事件映射流
	 */
	public Flux<Map<String, Object>> streamSessionChat(String sessionId, String input) {
		return this.requestLimiter.guard(this.webClient.post()
			.uri(HermesApiConstants.API_SESSIONS_CHAT_STREAM, sessionId)
			.accept(MediaType.TEXT_EVENT_STREAM).bodyValue(Map.of("input", input)).retrieve()
			.bodyToFlux(Map.class).map(m -> (Map<String, Object>) m));
	}

	// ========================================================================
	// Session Models
	// ========================================================================

	/** 创建会话请求。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record SessionCreateRequest(@JsonProperty("title") String title) {}

	/** Hermes 会话摘要及其层级、时间和扩展元数据。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Session(@JsonProperty("id") String id, @JsonProperty("title") String title,
			@JsonProperty("parent_id") String parentId, @JsonProperty("created_at") String createdAt,
			@JsonProperty("updated_at") String updatedAt, @JsonProperty("metadata") Map<String, Object> metadata) {}


	// ========================================================================
	// Request / Response Models — Chat Completions
	// ========================================================================

	/** OpenAI 兼容的聊天补全请求。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatRequest(@JsonProperty("model") String model, @JsonProperty("messages") List<Message> messages,
			@JsonProperty("stream") Boolean stream, @JsonProperty("tools") List<Tool> tools,
			@JsonProperty("tool_choice") Object toolChoice, @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
			@JsonProperty("max_tokens") Integer maxTokens, @JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP, @JsonProperty("frequency_penalty") Double frequencyPenalty,
			@JsonProperty("presence_penalty") Double presencePenalty, @JsonProperty("seed") Integer seed,
			@JsonProperty("stop") Object stop, @JsonProperty("user") String user,
			@JsonProperty("stream_options") StreamOptions streamOptions,
			@JsonProperty("thinking") ThinkOption thinking) {

		/**
		 * 创建聊天补全请求构建器。
		 *
		 * @param model 请求模型标识
		 * @return 新构建器
		 * @throws IllegalArgumentException 模型标识为 {@code null} 时抛出
		 */
		public static Builder builder(String model) { return new Builder(model); }

		/** 流式响应附加选项。 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

		/** 模型可调用的工具定义。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Tool(@JsonProperty("type") Type type, @JsonProperty("function") Function function) {
			/**
			 * 创建函数工具定义。
			 *
			 * @param f 函数协议定义
			 */
			public Tool(Function f) { this(Type.FUNCTION, f); }
			/** 工具类型；当前协议仅支持函数工具。 */
			public enum Type { @JsonProperty("function") FUNCTION }
			/** 函数工具的名称、说明和 JSON Schema 参数。 */
			public record Function(@JsonProperty("name") String name, @JsonProperty("description") String description,
					@JsonProperty("parameters") Map<String, Object> parameters) {}
		}

		/** 聊天补全请求构建器。 */
		public static final class Builder {
			/** 请求模型标识。 */
			private final String model;
			/** 对话消息列表。 */
			private List<Message> messages = List.of();
			/** 是否请求流式响应。 */
			private boolean stream;
			/** 可调用工具定义列表。 */
			private List<Tool> tools = List.of();
			/** 工具选择策略。 */
			private Object toolChoice;
			/** 最大补全令牌数。 */
			private Integer maxCompletionTokens;
			/** OpenAI 兼容的旧版最大令牌数字段。 */
			private Integer maxTokens;
			/** 采样温度。 */
			private Double temperature;
			/** 核采样概率阈值。 */
			private Double topP;
			/** 频率惩罚系数。 */
			private Double frequencyPenalty;
			/** 存在惩罚系数。 */
			private Double presencePenalty;
			/** 随机种子。 */
			private Integer seed;
			/** 字符串或字符串数组形式的停止条件。 */
			private Object stop;
			/** 最终用户标识。 */
			private String user;
			/** 流式响应附加选项。 */
			private StreamOptions streamOptions;
			/** 模型思考选项。 */
			private ThinkOption thinking;

			/**
			 * 创建指定模型的请求构建器。
			 *
			 * @param m 请求模型标识
			 * @throws IllegalArgumentException 模型标识为 {@code null} 时抛出
			 */
			public Builder(String m) { Assert.notNull(m, "model must not be null"); this.model = m; }
			/** @param v 消息列表 @return 当前构建器 */
			public Builder messages(List<Message> v) { messages = v; return this; }
			/** @param v 是否请求流式响应 @return 当前构建器 */
			public Builder stream(boolean v) { stream = v; return this; }
			/** @param v 可调用工具列表 @return 当前构建器 */
			public Builder tools(List<Tool> v) { tools = v; return this; }
			/** @param v 工具选择策略 @return 当前构建器 */
			public Builder toolChoice(Object v) { toolChoice = v; return this; }
			/** @param v 最大补全令牌数 @return 当前构建器 */
			public Builder maxCompletionTokens(Integer v) { maxCompletionTokens = v; return this; }
			/** @param v 兼容字段最大令牌数 @return 当前构建器 */
			public Builder maxTokens(Integer v) { maxTokens = v; return this; }
			/** @param v 采样温度 @return 当前构建器 */
			public Builder temperature(Double v) { temperature = v; return this; }
			/** @param v 核采样概率阈值 @return 当前构建器 */
			public Builder topP(Double v) { topP = v; return this; }
			/** @param v 频率惩罚系数 @return 当前构建器 */
			public Builder frequencyPenalty(Double v) { frequencyPenalty = v; return this; }
			/** @param v 存在惩罚系数 @return 当前构建器 */
			public Builder presencePenalty(Double v) { presencePenalty = v; return this; }
			/** @param v 随机种子 @return 当前构建器 */
			public Builder seed(Integer v) { seed = v; return this; }
			/** @param v 字符串或字符串数组形式的停止条件 @return 当前构建器 */
			public Builder stop(Object v) { stop = v; return this; }
			/** @param v 最终用户标识 @return 当前构建器 */
			public Builder user(String v) { user = v; return this; }
			/** @param v 流式响应附加选项 @return 当前构建器 */
			public Builder streamOptions(StreamOptions v) { streamOptions = v; return this; }
			/** @param v 模型思考选项 @return 当前构建器 */
			public Builder thinking(ThinkOption v) { thinking = v; return this; }
			/** @return 使用当前字段创建的不可变聊天补全请求 */
			public ChatRequest build() { return new ChatRequest(model, messages, stream, tools, toolChoice, maxCompletionTokens,
				maxTokens, temperature, topP, frequencyPenalty, presencePenalty, seed, stop, user, streamOptions, thinking); }
		}
	}

	/** OpenAI 兼容的聊天消息。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(@JsonProperty("role") Role role, @JsonProperty("content") Object content,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls, @JsonProperty("tool_call_id") String toolCallId,
			@JsonProperty("name") String name) {

		/**
		 * 创建指定角色的消息构建器。
		 *
		 * @param role 消息角色
		 * @return 新构建器
		 */
		public static Builder builder(Role role) { return new Builder(role); }

		/** 聊天消息角色。 */
		public enum Role {
			@JsonProperty("system") SYSTEM, @JsonProperty("user") USER,
			@JsonProperty("assistant") ASSISTANT, @JsonProperty("tool") TOOL
		}

		/** 数组格式用户消息的内容片段，可表达文本或内联图片。 */
		/** 图片内容片段的地址和清晰度提示。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ContentPart(@JsonProperty("type") String type, @JsonProperty("text") String text,
				@JsonProperty("image_url") ImageUrl imageUrl) {}

		/** 消息中的工具调用；流式响应使用 {@code index} 关联参数碎片。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ImageUrl(@JsonProperty("url") String url, @JsonProperty("detail") String detail) {}

		/** 工具调用函数名称及其 JSON 字符串参数。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCall(@JsonProperty("index") Integer index, @JsonProperty("id") String id,
				@JsonProperty("type") String type, @JsonProperty("function") ToolCallFunction function) {
			/**
			 * 创建不带流式索引的完整工具调用。
			 *
			 * @param id 工具调用标识
			 * @param type 工具类型
			 * @param function 函数调用信息
			 */
			public ToolCall(String id, String type, ToolCallFunction function) {
				this(null, id, type, function);
			}
		}

		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCallFunction(@JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {}

		/** 聊天消息构建器。 */
		public static final class Builder {
			/** 消息角色。 */
			private final Role role;
			/** 文本或数组格式的消息内容。 */
			private Object content;
			/** Assistant 消息携带的工具调用。 */
			private List<ToolCall> toolCalls;
			/** Tool 消息对应的工具调用标识。 */
			private String toolCallId;
			/** 消息或工具名称。 */
			private String name;

			/** @param r 消息角色 */
			public Builder(Role r) { role = r; }
			/** @param v 文本内容 @return 当前构建器 */
			public Builder content(String v) { content = v; return this; }
			/** @param v 数组格式内容片段 @return 当前构建器 */
			public Builder content(List<ContentPart> v) { content = v; return this; }
			/** @param v 工具调用列表 @return 当前构建器 */
			public Builder toolCalls(List<ToolCall> v) { toolCalls = v; return this; }
			/** @param v 对应的工具调用标识 @return 当前构建器 */
			public Builder toolCallId(String v) { toolCallId = v; return this; }
			/** @param v 消息或工具名称 @return 当前构建器 */
			public Builder name(String v) { name = v; return this; }
			/** @return 使用当前字段创建的不可变消息 */
			public Message build() { return new Message(role, content, toolCalls, toolCallId, name); }
		}
	}

	/** OpenAI 兼容的聊天补全响应或流式响应块。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponse(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("model") String model,
			@JsonProperty("choices") List<Choice> choices, @JsonProperty("usage") Usage usage) {

		/** 响应候选项，非流式使用 {@code message}，流式使用 {@code delta}。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Choice(@JsonProperty("index") Integer index, @JsonProperty("message") Message message,
				@JsonProperty("delta") Message delta, @JsonProperty("finish_reason") String finishReason) {}

		/** 请求、补全及总令牌用量。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
				@JsonProperty("completion_tokens") Integer completionTokens,
				@JsonProperty("total_tokens") Integer totalTokens,
				@JsonProperty("prompt_tokens_details") TokenDetails promptTokensDetails,
				@JsonProperty("completion_tokens_details") TokenDetails completionTokensDetails) {

			/** 缓存令牌和推理令牌等细分用量。 */
			@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
			public record TokenDetails(@JsonProperty("cached_tokens") Integer cachedTokens,
					@JsonProperty("reasoning_tokens") Integer reasoningTokens) {}
		}
	}

	// ========================================================================
	// Models — Responses API
	// ========================================================================

	/** Responses API 请求。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseRequest(
			@JsonProperty("model") String model, @JsonProperty("input") Object input,
			@JsonProperty("instructions") String instructions,
			@JsonProperty("previous_response_id") String previousResponseId,
			@JsonProperty("conversation") String conversation, @JsonProperty("store") Boolean store,
			@JsonProperty("stream") Boolean stream, @JsonProperty("max_output_tokens") Integer maxOutputTokens,
			@JsonProperty("temperature") Double temperature, @JsonProperty("top_p") Double topP,
			@JsonProperty("user") String user) {}

	/** Responses API 响应。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Response(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("status") String status, @JsonProperty("model") String model,
			@JsonProperty("output") List<OutputItem> output, @JsonProperty("usage") ChatResponse.Usage usage) {

		/** Responses API 输出项，可表示消息、函数调用或函数输出。 */
		@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
		public record OutputItem(@JsonProperty("type") String type, @JsonProperty("name") String name,
				@JsonProperty("arguments") String arguments, @JsonProperty("call_id") String callId,
				@JsonProperty("output") String output, @JsonProperty("role") String role,
				@JsonProperty("content") List<Map<String, Object>> content) {}
	}

	// ========================================================================
	// Models — Models listing
	// ========================================================================

	/** 模型列表响应。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ListModelResponse(@JsonProperty("object") String object, @JsonProperty("data") List<ModelData> data) {}

	/** 模型列表中的单个模型元数据。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelData(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission) {}

	/** 单模型查询响应。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelResponse(@JsonProperty("id") String id, @JsonProperty("object") String object,
			@JsonProperty("created") Long created, @JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission) {}

	// ========================================================================
	// Models — Capabilities
	// ========================================================================

	/** Hermes 服务端能力和特性开关。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Capabilities(@JsonProperty("object") String object, @JsonProperty("platform") String platform,
			@JsonProperty("model") String model, @JsonProperty("auth") Map<String, Object> auth,
			@JsonProperty("features") Map<String, Object> features,
			@JsonProperty("session_key_header") String sessionKeyHeader) {}

	// ========================================================================
	// Models — Runs API
	// ========================================================================

	/** 创建 Agent 运行的请求。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record RunRequest(@JsonProperty("input") String input, @JsonProperty("model") String model,
			@JsonProperty("session_id") String sessionId, @JsonProperty("instructions") String instructions,
			@JsonProperty("previous_response_id") String previousResponseId,
			@JsonProperty("conversation") String conversation,
			@JsonProperty("conversation_history") List<Message> conversationHistory) {}

	/** Agent 运行状态和结果。 */
	@JsonInclude(JsonInclude.Include.NON_NULL) @JsonIgnoreProperties(ignoreUnknown = true)
	public record Run(@JsonProperty("object") String object, @JsonProperty("run_id") String runId,
			@JsonProperty("status") String status, @JsonProperty("session_id") String sessionId,
			@JsonProperty("model") String model, @JsonProperty("output") String output,
			@JsonProperty("usage") ChatResponse.Usage usage) {}


	// ========================================================================
	// Jobs API (background scheduled work)
	// ========================================================================

	@SuppressWarnings("unchecked")
	/**
	 * 列出后台任务。
	 *
	 * @return 后台任务对象映射列表
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public List<Map<String, Object>> listJobs() {
		return this.requestLimiter.execute(() -> (List<Map<String, Object>>) (List<?>) this.restClient
			.get().uri(HermesApiConstants.API_JOBS).retrieve().body(List.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 创建后台任务。
	 *
	 * @param job 后台任务定义
	 * @return 创建后的任务对象
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> createJob(Map<String, Object> job) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_JOBS).body(job).retrieve().body(Map.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 查询后台任务。
	 *
	 * @param jobId 任务标识
	 * @return 任务对象
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> getJob(String jobId) {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri(HermesApiConstants.API_JOBS_BY_ID, jobId).retrieve().body(Map.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 局部更新后台任务。
	 *
	 * @param jobId 任务标识
	 * @param patch 待更新字段映射
	 * @return 更新后的任务对象
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> updateJob(String jobId, Map<String, Object> patch) {
		return this.requestLimiter.execute(() -> this.restClient.patch()
			.uri(HermesApiConstants.API_JOBS_BY_ID, jobId).body(patch).retrieve().body(Map.class));
	}

	/**
	 * 删除后台任务。
	 *
	 * @param jobId 任务标识
	 * @return 服务端返回 2xx 状态时为 {@code true}
	 * @throws IllegalArgumentException 任务标识为空白时抛出
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public boolean deleteJob(String jobId) {
		Assert.hasText(jobId, "jobId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.delete()
			.uri(HermesApiConstants.API_JOBS_BY_ID, jobId).retrieve()
			.toBodilessEntity().getStatusCode().is2xxSuccessful());
	}

	@SuppressWarnings("unchecked")
	/**
	 * 暂停后台任务。
	 *
	 * @param jobId 任务标识
	 * @return 暂停后的任务对象
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> pauseJob(String jobId) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_JOBS_PAUSE, jobId).retrieve().body(Map.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 恢复后台任务。
	 *
	 * @param jobId 任务标识
	 * @return 恢复后的任务对象
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> resumeJob(String jobId) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_JOBS_RESUME, jobId).retrieve().body(Map.class));
	}

	@SuppressWarnings("unchecked")
	/**
	 * 立即触发一次后台任务运行。
	 *
	 * @param jobId 任务标识
	 * @return 本次触发结果
	 * @throws java.util.concurrent.RejectedExecutionException 并发请求达到上限时抛出
	 */
	public Map<String, Object> runJobNow(String jobId) {
		return this.requestLimiter.execute(() -> this.restClient.post()
			.uri(HermesApiConstants.API_JOBS_RUN, jobId).retrieve().body(Map.class));
	}

	// ========================================================================
	// Builder
	// ========================================================================

	/** Hermes API 客户端构建器。 */
	public static final class Builder {
		/** API Server 基础地址。 */
		private String baseUrl = HermesApiConstants.DEFAULT_BASE_URL;

		/** 用于派生同步客户端的 RestClient 构建器。 */
		private RestClient.Builder restClientBuilder = RestClient.builder();

		/** 用于派生响应式客户端的 WebClient 构建器。 */
		private WebClient.Builder webClientBuilder = WebClient.builder();

		/** 同步请求的 HTTP 状态错误处理器。 */
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;

		/** 聊天 SSE 解析异常处理器。 */
		private SseErrorHandler sseErrorHandler = SseErrorHandler.DEFAULT;

		/** 同步、异步与流式请求共享的并发上限。 */
		private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;

		/**
		 * 设置 Hermes API Server 基础地址。
		 *
		 * @param v 非空白基础地址
		 * @return 当前构建器
		 * @throws IllegalArgumentException 地址为空白时抛出
		 */
		public Builder baseUrl(String v) { Assert.hasText(v, "baseUrl must not be empty"); baseUrl = v; return this; }
		/**
		 * 设置同步 HTTP 客户端构建器。
		 *
		 * @param v RestClient 构建器
		 * @return 当前构建器
		 * @throws IllegalArgumentException 构建器为 {@code null} 时抛出
		 */
		public Builder restClientBuilder(RestClient.Builder v) { Assert.notNull(v, "restClientBuilder must not be null"); restClientBuilder = v; return this; }
		/**
		 * 设置响应式 HTTP 客户端构建器。
		 *
		 * @param v WebClient 构建器
		 * @return 当前构建器
		 * @throws IllegalArgumentException 构建器为 {@code null} 时抛出
		 */
		public Builder webClientBuilder(WebClient.Builder v) { Assert.notNull(v, "webClientBuilder must not be null"); webClientBuilder = v; return this; }
		/**
		 * 设置同步响应错误处理器。
		 *
		 * @param v 响应错误处理器
		 * @return 当前构建器
		 * @throws IllegalArgumentException 处理器为 {@code null} 时抛出
		 */
		public Builder responseErrorHandler(ResponseErrorHandler v) { Assert.notNull(v, "responseErrorHandler must not be null"); responseErrorHandler = v; return this; }
		/**
		 * 设置流式响应解析异常处理器。
		 *
		 * @param v SSE 异常处理器
		 * @return 当前构建器
		 * @throws IllegalArgumentException 处理器为 {@code null} 时抛出
		 */
		public Builder sseErrorHandler(SseErrorHandler v) { Assert.notNull(v, "sseErrorHandler must not be null"); sseErrorHandler = v; return this; }
		/**
		 * 设置同步、异步与流式请求共享的并发上限。
		 *
		 * @param v 大于零的最大并发请求数
		 * @return 当前构建器
		 * @throws IllegalArgumentException 上限小于或等于零时抛出
		 */
		public Builder maxConcurrentRequests(int v) { Assert.isTrue(v > 0, "maxConcurrentRequests must be greater than zero"); maxConcurrentRequests = v; return this; }
		/**
		 * 创建 Hermes API 客户端。
		 *
		 * @return 使用当前连接、错误处理和并发配置创建的客户端
		 */
		public HermesApi build() { return new HermesApi(baseUrl, restClientBuilder, webClientBuilder,
			responseErrorHandler, sseErrorHandler, maxConcurrentRequests); }
	}
}
