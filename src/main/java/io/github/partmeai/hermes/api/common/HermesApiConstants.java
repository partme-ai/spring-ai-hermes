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
 * Common constants for the Hermes API Server.
 *
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
public final class HermesApiConstants {

	// -----------------------------------------------------------------------
	// Connection defaults
	// -----------------------------------------------------------------------

	public static final String DEFAULT_BASE_URL = "http://localhost:8642";
	public static final String PROVIDER_NAME = "hermes-agent";
	public static final String DEFAULT_MODEL = "hermes-agent";

	// -----------------------------------------------------------------------
	// HTTP header names
	// -----------------------------------------------------------------------

	/** Stable per-channel identifier for long-term memory scoping. Max 256 chars. */
	public static final String HEADER_SESSION_KEY = "X-Hermes-Session-Key";
	/** Transcript-scoped session identifier. Rotates on conversation reset. */
	public static final String HEADER_SESSION_ID = "X-Hermes-Session-Id";

	// -----------------------------------------------------------------------
	// API endpoint paths
	// -----------------------------------------------------------------------

	/** Chat Completions (OpenAI-compatible). */
	public static final String V1_CHAT_COMPLETIONS = "/v1/chat/completions";
	/** Responses API (server-side conversation state). */
	public static final String V1_RESPONSES = "/v1/responses";
	/** Single stored response by id. */
	public static final String V1_RESPONSES_BY_ID = "/v1/responses/{id}";
	/** Model listing / discovery. */
	public static final String V1_MODELS = "/v1/models";
	/** Single model detail. */
	public static final String V1_MODELS_BY_ID = "/v1/models/{id}";
	/** Health check. */
	public static final String HEALTH = "/health";
	/** Health check (OpenAI-compatible prefix). */
	public static final String V1_HEALTH = "/v1/health";
	/** Extended health check. */
	public static final String HEALTH_DETAILED = "/health/detailed";
	/** Machine-readable capabilities / feature flags. */
	public static final String V1_CAPABILITIES = "/v1/capabilities";
	/** Agent skill listing. */
	public static final String V1_SKILLS = "/v1/skills";
	/** Agent toolset listing. */
	public static final String V1_TOOLSETS = "/v1/toolsets";

	// -----------------------------------------------------------------------
	// Runs API paths
	// -----------------------------------------------------------------------

	/** Create a new agent run. */
	public static final String V1_RUNS = "/v1/runs";
	/** Run status / result by id. */
	public static final String V1_RUNS_BY_ID = "/v1/runs/{id}";
	/** SSE event stream for a run. */
	public static final String V1_RUNS_EVENTS = "/v1/runs/{id}/events";
	/** Stop a running agent turn. */
	public static final String V1_RUNS_STOP = "/v1/runs/{id}/stop";
	/** Resolve a pending approval. */
	public static final String V1_RUNS_APPROVAL = "/v1/runs/{id}/approval";

	private HermesApiConstants() {}
}
