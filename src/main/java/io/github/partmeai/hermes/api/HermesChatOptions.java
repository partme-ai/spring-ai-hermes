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
 * Strongly-typed chat options for the Hermes API Server.
 * <p>
 * Standard OpenAI fields (temperature, top_p, frequency_penalty, etc.) are sent
 * in the JSON request body. Hermes-specific fields ({@code X-Hermes-Session-Key},
 * {@code X-Hermes-Session-Id}) are sent as HTTP request headers.
 * <p>
 * <strong>Note:</strong> The {@code model} field is accepted but cosmetic —
 * the actual LLM model is configured server-side.
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@JsonInclude(Include.NON_NULL)
public class HermesChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	// Standard OpenAI chat completion fields (JSON body)
	@JsonProperty("model") private String model;
	@JsonProperty("temperature") private Double temperature;
	@JsonProperty("top_p") private Double topP;
	@JsonIgnore private Integer topK;
	@JsonProperty("frequency_penalty") private Double frequencyPenalty;
	@JsonProperty("presence_penalty") private Double presencePenalty;
	@JsonProperty("seed") private Integer seed;
	@JsonProperty("stop") private List<String> stop;
	@JsonProperty("max_tokens") private Integer maxTokens;
	@JsonProperty("user") private String user;
	@JsonProperty("thinking") private ThinkOption thinking;

	// Hermes-specific HTTP header fields (not in JSON body)
	@JsonIgnore private String hermesSessionKey;
	@JsonIgnore private String hermesSessionId;

	// Spring AI Tool Calling (managed by Spring AI, not sent to API)
	@JsonIgnore private Boolean internalToolExecutionEnabled;
	@JsonIgnore private List<ToolCallback> toolCallbacks = new ArrayList<>();
	@JsonIgnore private Set<String> toolNames = new HashSet<>();
	@JsonIgnore private Map<String, Object> toolContext;
	@JsonIgnore private Object format;

	public static Builder builder() { return new Builder(); }

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
	 * Build a map of Hermes-specific HTTP header values from these options.
	 * <p>
	 * {@code X-Hermes-Session-Key}: max 256 chars, control characters stripped.
	 * {@code X-Hermes-Session-Id}: transcript-scoped identifier.
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

	public Map<String, Object> toMap() { return ModelOptionsUtils.objectToMap(this); }

	@Override public HermesChatOptions copy() { return fromOptions(this); }

	@Override public String getModel() { return model; }
	public void setModel(String model) { this.model = model; }
	@Override public Double getTemperature() { return temperature; }
	public void setTemperature(Double v) { this.temperature = v; }
	@Override public Double getTopP() { return topP; }
	public void setTopP(Double v) { this.topP = v; }
	@Override public Integer getTopK() { return topK; }
	public void setTopK(Integer v) { this.topK = v; }
	@Override public Double getFrequencyPenalty() { return frequencyPenalty; }
	public void setFrequencyPenalty(Double v) { this.frequencyPenalty = v; }
	@Override public Double getPresencePenalty() { return presencePenalty; }
	public void setPresencePenalty(Double v) { this.presencePenalty = v; }
	public Integer getSeed() { return seed; }
	public void setSeed(Integer v) { this.seed = v; }
	@Override @JsonIgnore public List<String> getStopSequences() { return getStop(); }
	@JsonIgnore public void setStopSequences(List<String> v) { setStop(v); }
	public List<String> getStop() { return stop; }
	public void setStop(List<String> v) { this.stop = v; }
	@Override @JsonIgnore public Integer getMaxTokens() { return maxTokens; }
	@JsonIgnore public void setMaxTokens(Integer v) { this.maxTokens = v; }
	public String getUser() { return user; }
	public void setUser(String v) { this.user = v; }
	public ThinkOption getThinking() { return thinking; }
	public void setThinking(ThinkOption v) { this.thinking = v; }
	public String getHermesSessionKey() { return hermesSessionKey; }
	public void setHermesSessionKey(String v) { this.hermesSessionKey = v; }
	public String getHermesSessionId() { return hermesSessionId; }
	public void setHermesSessionId(String v) { this.hermesSessionId = v; }
	@Override @Nullable @JsonIgnore public Boolean getInternalToolExecutionEnabled() { return internalToolExecutionEnabled; }
	@Override @JsonIgnore public void setInternalToolExecutionEnabled(@Nullable Boolean v) { this.internalToolExecutionEnabled = v; }
	@Override @JsonIgnore public List<ToolCallback> getToolCallbacks() { return toolCallbacks; }
	@Override @JsonIgnore public void setToolCallbacks(List<ToolCallback> v) {
		Assert.notNull(v, "toolCallbacks cannot be null"); Assert.noNullElements(v, "toolCallbacks cannot contain null elements"); this.toolCallbacks = v;
	}
	@Override @JsonIgnore public Set<String> getToolNames() { return toolNames; }
	@Override @JsonIgnore public void setToolNames(Set<String> v) {
		Assert.notNull(v, "toolNames cannot be null"); Assert.noNullElements(v, "toolNames cannot contain null elements");
		v.forEach(t -> Assert.hasText(t, "toolNames cannot contain empty elements")); this.toolNames = v;
	}
	@Override @Nullable @JsonIgnore public Map<String, Object> getToolContext() { return toolContext; }
	@Override @JsonIgnore public void setToolContext(Map<String, Object> v) { this.toolContext = v; }
	@Override @JsonIgnore public String getOutputSchema() {
		if (format == null) return null;
		if (format instanceof String s) return s;
		return ModelOptionsUtils.toJsonString(format);
	}
	@Override @JsonIgnore public void setOutputSchema(String v) { this.format = v != null ? ModelOptionsUtils.jsonToMap(v) : null; }

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

	@Override public int hashCode() {
		return Objects.hash(model, temperature, topP, topK, frequencyPenalty, presencePenalty, seed, stop, maxTokens,
			user, thinking, hermesSessionKey, hermesSessionId, format, toolCallbacks, toolNames, toolContext, internalToolExecutionEnabled);
	}

	public static final class Builder {
		private final HermesChatOptions o = new HermesChatOptions();
		public Builder model(String v) { o.model = v; return this; }
		public Builder model(HermesModel v) { o.model = v.getName(); return this; }
		public Builder temperature(Double v) { o.temperature = v; return this; }
		public Builder topP(Double v) { o.topP = v; return this; }
		public Builder topK(Integer v) { o.topK = v; return this; }
		public Builder frequencyPenalty(Double v) { o.frequencyPenalty = v; return this; }
		public Builder presencePenalty(Double v) { o.presencePenalty = v; return this; }
		public Builder seed(Integer v) { o.seed = v; return this; }
		public Builder stop(List<String> v) { o.stop = v; return this; }
		public Builder maxTokens(Integer v) { o.maxTokens = v; return this; }
		public Builder user(String v) { o.user = v; return this; }
			public Builder thinking(ThinkOption v) { o.thinking = v; return this; }
		public Builder hermesSessionKey(String v) { o.hermesSessionKey = v; return this; }
		public Builder hermesSessionId(String v) { o.hermesSessionId = v; return this; }
		public Builder outputSchema(String v) { o.setOutputSchema(v); return this; }
		public Builder internalToolExecutionEnabled(@Nullable Boolean v) { o.setInternalToolExecutionEnabled(v); return this; }
		public Builder toolCallbacks(List<ToolCallback> v) { o.setToolCallbacks(v); return this; }
		public Builder toolCallbacks(ToolCallback... v) { Assert.notNull(v, "toolCallbacks cannot be null"); o.toolCallbacks.addAll(Arrays.asList(v)); return this; }
		public Builder toolNames(Set<String> v) { o.setToolNames(v); return this; }
		public Builder toolNames(String... v) { Assert.notNull(v, "toolNames cannot be null"); o.toolNames.addAll(Set.of(v)); return this; }
		public Builder toolContext(Map<String, Object> v) { o.toolContext = v; return this; }
		public HermesChatOptions build() { return o; }
	}
}
