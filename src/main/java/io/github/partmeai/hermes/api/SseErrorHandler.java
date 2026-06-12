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

import reactor.core.publisher.Mono;

import io.github.partmeai.hermes.api.HermesApi.ChatResponse;

/**
 * Strategy for handling SSE parse errors during streaming chat completions.
 * <p>
 * SSE streams end with a {@code data: [DONE]} sentinel — a JSON array, not a
 * chat completion object. The Jackson SSE decoder fails to deserialize it, and
 * this handler decides whether to suppress that error (completing the stream
 * gracefully) or propagate it.
 * <p>
 * Custom implementations can be registered via
 * {@link HermesApi.Builder#sseErrorHandler(SseErrorHandler)}.
 *
 * @see HermesApi.Builder#sseErrorHandler(SseErrorHandler)
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api#streaming-sse">Hermes Streaming SSE</a>
 */
@FunctionalInterface
public interface SseErrorHandler {

	/**
	 * Inspect an SSE parse error and decide how to proceed.
	 * @param cause the parse error thrown by the SSE decoder
	 * @return {@code Mono.empty()} to suppress the error and complete the stream,
	 *         or {@code Mono.error(cause)} to propagate it
	 */
	Mono<ChatResponse> handle(Throwable cause);

	// -----------------------------------------------------------------------
	// Built-in implementations
	// -----------------------------------------------------------------------

	/**
	 * Default handler: suppresses errors caused by the SSE {@code [DONE]}
	 * sentinel, propagates everything else.
	 * <p>
	 * The {@code [DONE]} event is a JSON array, not a chat completion object.
	 * Jackson throws a deserialization error with {@code START_ARRAY} token
	 * targeting {@code ChatResponse}. This handler matches that signature.
	 */
	SseErrorHandler DEFAULT = cause -> {
		String msg = cause.getMessage();
		if (msg != null
				&& msg.contains("START_ARRAY")
				&& msg.contains("ChatResponse")) {
			return Mono.empty();
		}
		return Mono.error(cause);
	};

	/**
	 * Lenient handler: suppresses ALL SSE parse errors, always completing the
	 * stream gracefully. Useful for debugging flaky providers.
	 */
	SseErrorHandler LENIENT = cause -> Mono.empty();

	/**
	 * Strict handler: never suppresses — every parse error propagates.
	 * The caller must handle the {@code [DONE]} sentinel itself.
	 */
	SseErrorHandler STRICT = Mono::error;
}
