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

import io.github.partmeai.hermes.api.HermesApi.ChatResponse;
import io.github.partmeai.hermes.api.HermesApi.Message;

/**
 * Helper methods for processing OpenAI-compatible chat completion responses.
 *
 * @since 1.0.0
 */
public final class HermesApiHelper {

	private HermesApiHelper() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	/**
	 * Check if a streaming chunk contains tool calls in its delta.
	 */
	public static boolean isStreamingToolCall(ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.choices() == null
				|| chatResponse.choices().isEmpty()) {
			return false;
		}
		var delta = chatResponse.choices().get(0).delta();
		return delta != null && delta.toolCalls() != null && !delta.toolCalls().isEmpty();
	}

	/**
	 * Check if a streaming chunk is the final chunk (has a finish_reason).
	 */
	public static boolean isStreamingDone(ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.choices() == null
				|| chatResponse.choices().isEmpty()) {
			return false;
		}
		return chatResponse.choices().get(0).finishReason() != null;
	}

	/**
	 * Extract content from the first choice's message (non-streaming) or delta (streaming).
	 */
	public static String getContent(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			return null;
		}
		var choice = response.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		return msg != null ? msg.content() : null;
	}

	/**
	 * Extract tool calls from the first choice's message or delta.
	 */
	public static List<Message.ToolCall> getToolCalls(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			return List.of();
		}
		var choice = response.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) {
			return List.of();
		}
		return msg.toolCalls();
	}
}
