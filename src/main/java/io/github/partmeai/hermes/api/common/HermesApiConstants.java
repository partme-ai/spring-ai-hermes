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

package io.github.partmeai.hermes.api.common;

/**
 * Hermes API Server 协议常量。
 *
 * <p>统一维护默认连接信息、会话请求头名称以及 Chat、Responses、Runs、Jobs、
 * Sessions 等 API 的相对路径，避免各客户端方法重复硬编码协议字符串。</p>
 *
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
public final class HermesApiConstants {

	// -----------------------------------------------------------------------
	// Connection defaults
	// -----------------------------------------------------------------------

	/** 默认本地 API Server 地址。 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8642";
	/** Spring AI 观测上下文使用的供应方名称。 */
	public static final String PROVIDER_NAME = "hermes-agent";
	/** 默认模型标识。 */
	public static final String DEFAULT_MODEL = "hermes-agent";

	// -----------------------------------------------------------------------
	// HTTP header names
	// -----------------------------------------------------------------------

	/** 用于长期记忆作用域的稳定通道标识请求头，最多 256 个字符。 */
	public static final String HEADER_SESSION_KEY = "X-Hermes-Session-Key";
	/** 会话记录级标识请求头，可在对话重置时轮换。 */
	public static final String HEADER_SESSION_ID = "X-Hermes-Session-Id";

	// -----------------------------------------------------------------------
	// API endpoint paths
	// -----------------------------------------------------------------------

	/** OpenAI 兼容的聊天补全路径。 */
	public static final String V1_CHAT_COMPLETIONS = "/v1/chat/completions";
	/** 由服务端维护对话状态的 Responses API 路径。 */
	public static final String V1_RESPONSES = "/v1/responses";
	/** 按标识查询或删除单个已存储响应的路径。 */
	public static final String V1_RESPONSES_BY_ID = "/v1/responses/{id}";
	/** 模型列表与发现路径。 */
	public static final String V1_MODELS = "/v1/models";
	/** 按标识查询单个模型详情的路径。 */
	public static final String V1_MODELS_BY_ID = "/v1/models/{id}";
	/** 基础健康检查路径。 */
	public static final String HEALTH = "/health";
	/** OpenAI 兼容前缀下的健康检查路径。 */
	public static final String V1_HEALTH = "/v1/health";
	/** 扩展健康检查路径。 */
	public static final String HEALTH_DETAILED = "/health/detailed";
	/** 机器可读能力与特性开关路径。 */
	public static final String V1_CAPABILITIES = "/v1/capabilities";
	/** Agent 技能列表路径。 */
	public static final String V1_SKILLS = "/v1/skills";
	/** Agent 工具集列表路径。 */
	public static final String V1_TOOLSETS = "/v1/toolsets";

	// -----------------------------------------------------------------------
	// Runs API paths
	// -----------------------------------------------------------------------

	/** 创建 Agent 运行的路径。 */
	public static final String V1_RUNS = "/v1/runs";
	/** 按标识查询运行状态或结果的路径。 */
	public static final String V1_RUNS_BY_ID = "/v1/runs/{id}";
	/** 运行 SSE 事件流路径。 */
	public static final String V1_RUNS_EVENTS = "/v1/runs/{id}/events";
	/** 停止运行中 Agent 轮次的路径。 */
	public static final String V1_RUNS_STOP = "/v1/runs/{id}/stop";
	/** 处理待决审批的路径。 */
	public static final String V1_RUNS_APPROVAL = "/v1/runs/{id}/approval";


	// -----------------------------------------------------------------------
	// Jobs API paths
	// -----------------------------------------------------------------------

	/** 后台任务列表与创建路径。 */
	public static final String API_JOBS = "/api/jobs";
	/** 按标识查询、更新或删除后台任务的路径。 */
	public static final String API_JOBS_BY_ID = "/api/jobs/{id}";
	/** 暂停后台任务的路径。 */
	public static final String API_JOBS_PAUSE = "/api/jobs/{id}/pause";
	/** 恢复后台任务的路径。 */
	public static final String API_JOBS_RESUME = "/api/jobs/{id}/resume";
	/** 立即运行后台任务的路径。 */
	public static final String API_JOBS_RUN = "/api/jobs/{id}/run";

	// -----------------------------------------------------------------------
	// Sessions API paths
	// -----------------------------------------------------------------------

	/** 会话列表与创建路径。 */
	public static final String API_SESSIONS = "/api/sessions";
	/** 按标识查询、更新或删除会话的路径。 */
	public static final String API_SESSIONS_BY_ID = "/api/sessions/{id}";
	/** 会话消息列表路径。 */
	public static final String API_SESSIONS_MESSAGES = "/api/sessions/{id}/messages";
	/** 分叉子会话的路径。 */
	public static final String API_SESSIONS_FORK = "/api/sessions/{id}/fork";
	/** 会话同步聊天路径。 */
	public static final String API_SESSIONS_CHAT = "/api/sessions/{id}/chat";
	/** 会话流式聊天路径。 */
	public static final String API_SESSIONS_CHAT_STREAM = "/api/sessions/{id}/chat/stream";

	/** 禁止实例化常量容器。 */
	private HermesApiConstants() {}
}
