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

import java.util.ArrayList;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Hermes API Server 的强类型聊天选项。
 *
 * <p>温度、采样、惩罚项等 OpenAI 兼容选项进入 JSON 请求体；
 * {@code X-Hermes-Session-Key} 与 {@code X-Hermes-Session-Id} 仅转换为 HTTP
 * 请求头。Spring AI 工具调用和结构化输出相关字段只在客户端侧参与编排，不直接发送。</p>
 *
 * <p>服务端接受 {@code model} 字段以兼容协议，但实际大模型由服务端配置。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@JsonInclude(Include.NON_NULL)
public class HermesChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	// Standard OpenAI chat completion fields (JSON body)
	/** 请求模型标识；实际大模型由服务端配置。 */
	@JsonProperty("model") private String model;
	/** 采样温度。 */
	@JsonProperty("temperature") private Double temperature;
	/** 核采样概率阈值。 */
	@JsonProperty("top_p") private Double topP;
	/** Spring AI 兼容的 Top-K 选项；Hermes 请求体忽略该字段。 */
	@JsonIgnore private Integer topK;
	/** 频率惩罚系数。 */
	@JsonProperty("frequency_penalty") private Double frequencyPenalty;
	/** 存在惩罚系数。 */
	@JsonProperty("presence_penalty") private Double presencePenalty;
	/** 随机种子。 */
	@JsonProperty("seed") private Integer seed;
	/** 停止序列列表。 */
	@JsonProperty("stop") private List<String> stop;
	/** 最大生成令牌数。 */
	@JsonProperty("max_tokens") private Integer maxTokens;
	/** 最终用户标识。 */
	@JsonProperty("user") private String user;
	/** 布尔型或等级型模型思考选项。 */
	@JsonProperty("thinking") private ThinkOption thinking;

	// Hermes-specific HTTP header fields (not in JSON body)
	/** 稳定的 Hermes 长期记忆作用域键。 */
	@JsonIgnore private String hermesSessionKey;
	/** Hermes 会话记录标识。 */
	@JsonIgnore private String hermesSessionId;

	// Spring AI Tool Calling (managed by Spring AI, not sent to API)
	/** 是否由 Spring AI 在客户端内部执行工具。 */
	@JsonIgnore private Boolean internalToolExecutionEnabled;
	/** 直接注册的工具回调。 */
	@JsonIgnore private List<ToolCallback> toolCallbacks = new ArrayList<>();
	/** 从工具上下文按名称解析的工具集合。 */
	@JsonIgnore private Set<String> toolNames = new HashSet<>();
	/** 传递给工具执行过程的上下文。 */
	@JsonIgnore private Map<String, Object> toolContext;
	/** 字符串或解析后映射形式的结构化输出 Schema。 */
	@JsonIgnore private Object format;

	/**
	 * 创建 Hermes 聊天选项构建器。
	 *
	 * @return 新构建器
	 */
	public static Builder builder() { return new Builder(); }

	/**
	 * 复制给定 Hermes 聊天选项的全部已支持字段。
	 *
	 * @param o 源选项
	 * @return 独立的选项副本
	 */
	public static HermesChatOptions fromOptions(HermesChatOptions o) {
		return builder().model(o.getModel()).temperature(o.getTemperature())
			.topP(o.getTopP()).topK(o.getTopK()).frequencyPenalty(o.getFrequencyPenalty())
			.presencePenalty(o.getPresencePenalty()).seed(o.getSeed()).stop(o.getStop())
			.maxTokens(o.getMaxTokens()).user(o.getUser()).thinking(o.getThinking())
			.hermesSessionKey(o.getHermesSessionKey()).hermesSessionId(o.getHermesSessionId())
			.outputSchema(o.getOutputSchema()).internalToolExecutionEnabled(o.getInternalToolExecutionEnabled())
			.toolCallbacks(o.getToolCallbacks()).toolNames(o.getToolNames()).toolContext(o.getToolContext()).build();
	}

	/**
	 * 将 Hermes 会话选项转换为 HTTP 请求头。
	 *
	 * <p>{@code X-Hermes-Session-Key} 会移除回车、换行和 NUL 字符，并截断到
	 * 256 个字符；{@code X-Hermes-Session-Id} 在有文本时原样加入。</p>
	 *
	 * @return 仅包含实际配置的 Hermes 会话请求头的新映射
	 */
	public Map<String, String> toHttpHeaders() {
		Map<String, String> headers = new HashMap<>();
		if (StringUtils.hasText(hermesSessionKey)) {
			String sanitized = hermesSessionKey.replace("\r", "").replace("\n", "").replace("\0", "");
			if (sanitized.length() > 256) sanitized = sanitized.substring(0, 256);
			headers.put(HermesApiConstants.HEADER_SESSION_KEY, sanitized);
		}
		if (StringUtils.hasText(hermesSessionId)) {
			headers.put(HermesApiConstants.HEADER_SESSION_ID, hermesSessionId);
		}
		return headers;
	}

	/**
	 * 将当前选项转换为通用属性映射。
	 *
	 * @return 由 Spring AI 模型选项工具生成的属性映射
	 */
	public Map<String, Object> toMap() { return ModelOptionsUtils.objectToMap(this); }

	/**
	 * 创建当前选项的副本。
	 *
	 * @return 独立的 Hermes 聊天选项
	 */
	@Override public HermesChatOptions copy() { return fromOptions(this); }

	/** @return 请求模型标识 */
	@Override public String getModel() { return model; }
	/** @param model 请求模型标识 */
	public void setModel(String model) { this.model = model; }
	/** @return 采样温度 */
	@Override public Double getTemperature() { return temperature; }
	/** @param v 采样温度 */
	public void setTemperature(Double v) { this.temperature = v; }
	/** @return 核采样概率阈值 */
	@Override public Double getTopP() { return topP; }
	/** @param v 核采样概率阈值 */
	public void setTopP(Double v) { this.topP = v; }
	/** @return 客户端保留的 Top-K 选项 */
	@Override public Integer getTopK() { return topK; }
	/** @param v 客户端保留的 Top-K 选项 */
	public void setTopK(Integer v) { this.topK = v; }
	/** @return 频率惩罚系数 */
	@Override public Double getFrequencyPenalty() { return frequencyPenalty; }
	/** @param v 频率惩罚系数 */
	public void setFrequencyPenalty(Double v) { this.frequencyPenalty = v; }
	/** @return 存在惩罚系数 */
	@Override public Double getPresencePenalty() { return presencePenalty; }
	/** @param v 存在惩罚系数 */
	public void setPresencePenalty(Double v) { this.presencePenalty = v; }
	/** @return 随机种子 */
	public Integer getSeed() { return seed; }
	/** @param v 随机种子 */
	public void setSeed(Integer v) { this.seed = v; }
	/** @return 停止序列列表 */
	@Override @JsonIgnore public List<String> getStopSequences() { return getStop(); }
	/** @param v 停止序列列表 */
	@JsonIgnore public void setStopSequences(List<String> v) { setStop(v); }
	/** @return Hermes 请求体中的停止条件列表 */
	public List<String> getStop() { return stop; }
	/** @param v Hermes 请求体中的停止条件列表 */
	public void setStop(List<String> v) { this.stop = v; }
	/** @return 最大生成令牌数 */
	@Override @JsonIgnore public Integer getMaxTokens() { return maxTokens; }
	/** @param v 最大生成令牌数 */
	@JsonIgnore public void setMaxTokens(Integer v) { this.maxTokens = v; }
	/** @return 最终用户标识 */
	public String getUser() { return user; }
	/** @param v 最终用户标识 */
	public void setUser(String v) { this.user = v; }
	/** @return 模型思考选项 */
	public ThinkOption getThinking() { return thinking; }
	/** @param v 模型思考选项 */
	public void setThinking(ThinkOption v) { this.thinking = v; }
	/** @return 稳定的 Hermes 长期记忆作用域键 */
	public String getHermesSessionKey() { return hermesSessionKey; }
	/** @param v 稳定的 Hermes 长期记忆作用域键 */
	public void setHermesSessionKey(String v) { this.hermesSessionKey = v; }
	/** @return Hermes 会话记录标识 */
	public String getHermesSessionId() { return hermesSessionId; }
	/** @param v Hermes 会话记录标识 */
	public void setHermesSessionId(String v) { this.hermesSessionId = v; }
	/** @return 是否由 Spring AI 在客户端内部执行工具 */
	@Override @Nullable @JsonIgnore public Boolean getInternalToolExecutionEnabled() { return internalToolExecutionEnabled; }
	/** @param v 是否由 Spring AI 在客户端内部执行工具 */
	@Override @JsonIgnore public void setInternalToolExecutionEnabled(@Nullable Boolean v) { this.internalToolExecutionEnabled = v; }
	/** @return 直接配置的工具回调列表 */
	@Override @JsonIgnore public List<ToolCallback> getToolCallbacks() { return toolCallbacks; }
	/**
	 * 设置直接配置的工具回调。
	 *
	 * @param v 非空且不含空元素的工具回调列表
	 * @throws IllegalArgumentException 列表为 {@code null} 或包含 {@code null} 时抛出
	 */
	@Override @JsonIgnore public void setToolCallbacks(List<ToolCallback> v) {
		Assert.notNull(v, "toolCallbacks cannot be null"); Assert.noNullElements(v, "toolCallbacks cannot contain null elements"); this.toolCallbacks = v;
	}
	/** @return 从工具上下文解析的工具名称集合 */
	@Override @JsonIgnore public Set<String> getToolNames() { return toolNames; }
	/**
	 * 设置待解析的工具名称。
	 *
	 * @param v 非空、不含空元素且每个名称均有文本的集合
	 * @throws IllegalArgumentException 集合为空引用、包含空元素或空白名称时抛出
	 */
	@Override @JsonIgnore public void setToolNames(Set<String> v) {
		Assert.notNull(v, "toolNames cannot be null"); Assert.noNullElements(v, "toolNames cannot contain null elements");
		v.forEach(t -> Assert.hasText(t, "toolNames cannot contain empty elements")); this.toolNames = v;
	}
	/** @return 工具执行上下文，可以为 {@code null} */
	@Override @Nullable @JsonIgnore public Map<String, Object> getToolContext() { return toolContext; }
	/** @param v 工具执行上下文 */
	@Override @JsonIgnore public void setToolContext(Map<String, Object> v) { this.toolContext = v; }
	/**
	 * 读取结构化输出 Schema。
	 *
	 * @return 字符串 Schema；内部格式为对象时返回其 JSON 字符串，为空时返回 {@code null}
	 */
	@Override @JsonIgnore public String getOutputSchema() {
		if (format == null) return null;
		if (format instanceof String s) return s;
		return ModelOptionsUtils.toJsonString(format);
	}
	/**
	 * 设置结构化输出 Schema。
	 *
	 * @param v JSON Schema 字符串；为 {@code null} 时清除内部格式
	 */
	@Override @JsonIgnore public void setOutputSchema(String v) { this.format = v != null ? ModelOptionsUtils.jsonToMap(v) : null; }

	/** {@inheritDoc} */
	@Override public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof HermesChatOptions t)) return false;
		return Objects.equals(model, t.model) && Objects.equals(temperature, t.temperature) && Objects.equals(topP, t.topP)
			&& Objects.equals(topK, t.topK) && Objects.equals(frequencyPenalty, t.frequencyPenalty)
			&& Objects.equals(presencePenalty, t.presencePenalty) && Objects.equals(seed, t.seed)
			&& Objects.equals(stop, t.stop) && Objects.equals(maxTokens, t.maxTokens) && Objects.equals(user, t.user)
			&& Objects.equals(thinking, t.thinking)
			&& Objects.equals(hermesSessionKey, t.hermesSessionKey) && Objects.equals(hermesSessionId, t.hermesSessionId)
			&& Objects.equals(format, t.format) && Objects.equals(toolCallbacks, t.toolCallbacks)
			&& Objects.equals(toolNames, t.toolNames) && Objects.equals(toolContext, t.toolContext)
			&& Objects.equals(internalToolExecutionEnabled, t.internalToolExecutionEnabled);
	}

	/** {@inheritDoc} */
	@Override public int hashCode() {
		return Objects.hash(model, temperature, topP, topK, frequencyPenalty, presencePenalty, seed, stop, maxTokens,
			user, thinking, hermesSessionKey, hermesSessionId, format, toolCallbacks, toolNames, toolContext, internalToolExecutionEnabled);
	}

	/**
	 * Hermes 聊天选项构建器。
	 *
	 * <p>逐项写入一个新的 {@link HermesChatOptions}，并在集合型工具配置入口复用
	 * 对象自身的非空校验。</p>
	 */
	public static final class Builder {
		/** 当前正在组装的可变 Hermes 聊天选项。 */
		private final HermesChatOptions o = new HermesChatOptions();
		/** @param v 模型标识 @return 当前构建器 */
		public Builder model(String v) { o.model = v; return this; }
		/** @param v Hermes 模型枚举 @return 当前构建器 */
		public Builder model(HermesModel v) { o.model = v.getName(); return this; }
		/** @param v 采样温度 @return 当前构建器 */
		public Builder temperature(Double v) { o.temperature = v; return this; }
		/** @param v 核采样概率阈值 @return 当前构建器 */
		public Builder topP(Double v) { o.topP = v; return this; }
		/** @param v 客户端保留的 Top-K 选项 @return 当前构建器 */
		public Builder topK(Integer v) { o.topK = v; return this; }
		/** @param v 频率惩罚系数 @return 当前构建器 */
		public Builder frequencyPenalty(Double v) { o.frequencyPenalty = v; return this; }
		/** @param v 存在惩罚系数 @return 当前构建器 */
		public Builder presencePenalty(Double v) { o.presencePenalty = v; return this; }
		/** @param v 随机种子 @return 当前构建器 */
		public Builder seed(Integer v) { o.seed = v; return this; }
		/** @param v 停止序列列表 @return 当前构建器 */
		public Builder stop(List<String> v) { o.stop = v; return this; }
		/** @param v 最大生成令牌数 @return 当前构建器 */
		public Builder maxTokens(Integer v) { o.maxTokens = v; return this; }
		/** @param v 最终用户标识 @return 当前构建器 */
		public Builder user(String v) { o.user = v; return this; }
		/** @param v 模型思考选项 @return 当前构建器 */
		public Builder thinking(ThinkOption v) { o.thinking = v; return this; }
		/** @param v Hermes 长期记忆作用域键 @return 当前构建器 */
		public Builder hermesSessionKey(String v) { o.hermesSessionKey = v; return this; }
		/** @param v Hermes 会话记录标识 @return 当前构建器 */
		public Builder hermesSessionId(String v) { o.hermesSessionId = v; return this; }
		/** @param v JSON Schema 字符串 @return 当前构建器 */
		public Builder outputSchema(String v) { o.setOutputSchema(v); return this; }
		/** @param v 是否由 Spring AI 内部执行工具 @return 当前构建器 */
		public Builder internalToolExecutionEnabled(@Nullable Boolean v) { o.setInternalToolExecutionEnabled(v); return this; }
		/**
		 * @param v 非空且不含空元素的工具回调列表
		 * @return 当前构建器
		 * @throws IllegalArgumentException 列表为 {@code null} 或包含 {@code null} 时抛出
		 */
		public Builder toolCallbacks(List<ToolCallback> v) { o.setToolCallbacks(v); return this; }
		/**
		 * @param v 工具回调数组
		 * @return 当前构建器
		 * @throws IllegalArgumentException 数组为 {@code null} 时抛出
		 */
		public Builder toolCallbacks(ToolCallback... v) { Assert.notNull(v, "toolCallbacks cannot be null"); o.toolCallbacks.addAll(Arrays.asList(v)); return this; }
		/**
		 * @param v 非空、不含空元素且名称有文本的工具名称集合
		 * @return 当前构建器
		 * @throws IllegalArgumentException 集合或元素不符合约束时抛出
		 */
		public Builder toolNames(Set<String> v) { o.setToolNames(v); return this; }
		/**
		 * @param v 工具名称数组
		 * @return 当前构建器
		 * @throws IllegalArgumentException 数组为 {@code null} 时抛出
		 */
		public Builder toolNames(String... v) { Assert.notNull(v, "toolNames cannot be null"); o.toolNames.addAll(Set.of(v)); return this; }
		/** @param v 工具执行上下文 @return 当前构建器 */
		public Builder toolContext(Map<String, Object> v) { o.toolContext = v; return this; }
		/** @return 当前构建器持有的 Hermes 聊天选项 */
		public HermesChatOptions build() { return o; }
	}
}
