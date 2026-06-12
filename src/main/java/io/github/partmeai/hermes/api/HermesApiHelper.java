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

	private HermesApiHelper() { throw new UnsupportedOperationException("Utility class"); }

	public static boolean isStreamingToolCall(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return false;
		var delta = r.choices().get(0).delta();
		return delta != null && delta.toolCalls() != null && !delta.toolCalls().isEmpty();
	}

	public static boolean isStreamingDone(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return false;
		return r.choices().get(0).finishReason() != null;
	}

	/** Extract content from the first choice's message (non-streaming) or delta (streaming). */
	public static String getContent(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return null;
		var choice = r.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.content() == null) return null;
		if (msg.content() instanceof String s) return s;
		return msg.content().toString();
	}

	/** Extract tool calls from the first choice's message or delta. */
	public static List<Message.ToolCall> getToolCalls(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return List.of();
		var choice = r.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) return List.of();
		return msg.toolCalls();
	}
}
