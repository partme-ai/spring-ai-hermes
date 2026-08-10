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
 * SSE 解析异常处理策略。
 *
 * <p>用于决定流式聊天响应发生解析异常时，是将异常转换为空发布者并正常结束，
 * 还是继续向下游传播。虽然当前客户端会先按字符串识别 {@code [DONE]} 结束标记，
 * 该策略仍负责处理供应方返回的异常 SSE 数据和兼容性场景。</p>
 *
 * <p>可通过 {@link HermesApi.Builder#sseErrorHandler(SseErrorHandler)} 注册自定义策略。</p>
 *
 * @see HermesApi.Builder#sseErrorHandler(SseErrorHandler)
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api#streaming-sse">Hermes Streaming SSE</a>
 */
@FunctionalInterface
public interface SseErrorHandler {

	/**
	 * 检查 SSE 解析异常并决定后续行为。
	 *
	 * @param cause SSE 解码或 JSON 映射产生的异常
	 * @return 用于替代失败数据块的发布者；返回空发布者表示抑制异常，返回错误发布者表示传播异常
	 */
	Mono<ChatResponse> handle(Throwable cause);

	// -----------------------------------------------------------------------
	// Built-in implementations
	// -----------------------------------------------------------------------

	/**
	 * 默认策略：仅抑制错误消息同时包含 {@code START_ARRAY} 与 {@code ChatResponse}
	 * 的解析异常，其余异常原样传播。
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

	/** 宽松策略：抑制全部 SSE 解析异常并正常结束当前替代序列。 */
	SseErrorHandler LENIENT = cause -> Mono.empty();

	/** 严格策略：不抑制任何解析异常，始终将原异常传播给下游。 */
	SseErrorHandler STRICT = Mono::error;
}
