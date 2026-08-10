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
 * OpenAI 兼容聊天响应辅助工具。
 *
 * <p>集中处理首个候选项的流式状态判断、文本内容提取和工具调用提取；对响应、
 * 候选项或消息缺失的情况返回安全的空值，供同步和流式模型适配逻辑复用。</p>
 *
 * @since 1.0.0
 */
public final class HermesApiHelper {

	private HermesApiHelper() { throw new UnsupportedOperationException("Utility class"); }

	/**
	 * 判断首个流式候选项是否携带工具调用碎片。
	 *
	 * @param r Hermes 聊天响应块，可以为 {@code null}
	 * @return 首个增量消息包含至少一个工具调用时返回 {@code true}
	 */
	public static boolean isStreamingToolCall(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return false;
		var delta = r.choices().get(0).delta();
		return delta != null && delta.toolCalls() != null && !delta.toolCalls().isEmpty();
	}

	/**
	 * 判断首个候选项是否已给出结束原因。
	 *
	 * @param r Hermes 聊天响应块，可以为 {@code null}
	 * @return 存在首个候选项且其结束原因非空时返回 {@code true}
	 */
	public static boolean isStreamingDone(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return false;
		return r.choices().get(0).finishReason() != null;
	}

	/**
	 * 提取首个候选项的消息内容。
	 *
	 * <p>非流式响应优先读取完整 {@code message}，否则读取流式 {@code delta}；
	 * 字符串原样返回，其他内容对象使用 {@link Object#toString()} 转换。</p>
	 *
	 * @param r Hermes 聊天响应或响应块，可以为 {@code null}
	 * @return 文本内容；响应结构不完整时返回 {@code null}
	 */
	public static String getContent(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return null;
		var choice = r.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.content() == null) return null;
		if (msg.content() instanceof String s) return s;
		return msg.content().toString();
	}

	/**
	 * 提取首个候选项的工具调用。
	 *
	 * @param r Hermes 聊天响应或响应块，可以为 {@code null}
	 * @return 工具调用列表；响应结构不完整或没有工具调用时返回不可变空列表
	 */
	public static List<Message.ToolCall> getToolCalls(ChatResponse r) {
		if (r == null || r.choices() == null || r.choices().isEmpty()) return List.of();
		var choice = r.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) return List.of();
		return msg.toolCalls();
	}
}
