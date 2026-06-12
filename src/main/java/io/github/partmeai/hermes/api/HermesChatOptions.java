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

/**
 * Strongly-typed chat options for the Hermes Gateway's OpenAI-compatible
 * {@code /v1/chat/completions} endpoint.
 * <p>
 * Standard OpenAI fields (temperature, top_p, frequency_penalty, etc.) are sent
 * in the JSON request body. Hermes-specific fields ({@code x-hermes-model},
 * {@code x-hermes-session-key}, etc.) are sent as HTTP request headers.
 *
 * @author Loong Wan
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api">Hermes OpenAI HTTP API</a>
 */
@JsonInclude(Include.NON_NULL)
public class HermesChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	// -----------------------------------------------------------------------
	// Standard OpenAI chat completion fields (JSON body)
	// -----------------------------------------------------------------------

	/**
	 * Hermes agent target id, e.g. "hermes/default" or "hermes/research".
	 */
	@JsonProperty("model")
	private String model;

	/**
	 * Sampling temperature (0.0–2.0).
	 */
	@JsonProperty("temperature")
	private Double temperature;

	/**
	 * Nucleus sampling probability.
	 */
	@JsonProperty("top_p")
	private Double topP;

	/**
	 * Top-k sampling. Not forwarded to the API (Hermes doesn't expose top_k).
	 */
	@JsonIgnore
	private Integer topK;

	/**
	 * Frequency penalty (-2.0 to 2.0).
	 */
	@JsonProperty("frequency_penalty")
	private Double frequencyPenalty;

	/**
	 * Presence penalty (-2.0 to 2.0).
	 */
	@JsonProperty("presence_penalty")
	private Double presencePenalty;

	/**
	 * Integer seed for reproducible output.
	 */
	@JsonProperty("seed")
	private Integer seed;

	/**
	 * Stop sequences (string or array of up to 4 strings).
	 */
	@JsonProperty("stop")
	private List<String> stop;

	/**
	 * Maximum completion tokens (maps to max_completion_tokens in the API).
	 */
	@JsonProperty("max_tokens")
	private Integer maxTokens;

	/**
	 * An OpenAI user identifier for stable session routing.
	 * Hermes derives a stable session key from this value.
	 */
	@JsonProperty("user")
	private String user;

	// -----------------------------------------------------------------------
	// Hermes-specific HTTP header fields (not in JSON body)
	// -----------------------------------------------------------------------

	/**
	 * Override the backend provider/model for the selected agent.
	 * Sent as the {@code x-hermes-model} HTTP header.
	 * Example: "openai/gpt-5.4" or "gpt-5.5".
	 */
	@JsonIgnore
	private String xOpenclawModel;

	/**
	 * Explicit session routing key.
	 * Sent as the {@code x-hermes-session-key} HTTP header.
	 */
	@JsonIgnore
	private String xOpenclawSessionKey;

	/**
	 * Synthetic ingress channel context for channel-aware prompts and policies.
	 * Sent as the {@code x-hermes-message-channel} HTTP header.
	 * Example: "slack", "discord".
	 */
	@JsonIgnore
	private String xOpenclawMessageChannel;

	/**
	 * Compatibility agent-id override.
	 * Sent as the {@code x-hermes-agent-id} HTTP header.
	 */
	@JsonIgnore
	private String xOpenclawAgentId;

	// -----------------------------------------------------------------------
	// Spring AI Tool Calling fields (managed by Spring AI, not sent to API)
	// -----------------------------------------------------------------------

	@JsonIgnore
	private Boolean internalToolExecutionEnabled;

	@JsonIgnore
	private List<ToolCallback> toolCallbacks = new ArrayList<>();

	@JsonIgnore
	private Set<String> toolNames = new HashSet<>();

	@JsonIgnore
	private Map<String, Object> toolContext = new HashMap<>();

	// -----------------------------------------------------------------------
	// Structured output (StructuredOutputChatOptions)
	// -----------------------------------------------------------------------

	@JsonIgnore
	private Object format;

	// -----------------------------------------------------------------------
	// Factory methods
	// -----------------------------------------------------------------------

	public static Builder builder() {
		return new Builder();
	}

	public static HermesChatOptions fromOptions(HermesChatOptions fromOptions) {
		return builder()
			.model(fromOptions.getModel())
			.temperature(fromOptions.getTemperature())
			.topP(fromOptions.getTopP())
			.topK(fromOptions.getTopK())
			.frequencyPenalty(fromOptions.getFrequencyPenalty())
			.presencePenalty(fromOptions.getPresencePenalty())
			.seed(fromOptions.getSeed())
			.stop(fromOptions.getStop())
			.maxTokens(fromOptions.getMaxTokens())
			.user(fromOptions.getUser())
			.xOpenclawModel(fromOptions.getXOpenclawModel())
			.xOpenclawSessionKey(fromOptions.getXOpenclawSessionKey())
			.xOpenclawMessageChannel(fromOptions.getXOpenclawMessageChannel())
			.xOpenclawAgentId(fromOptions.getXOpenclawAgentId())
			.outputSchema(fromOptions.getOutputSchema())
			.internalToolExecutionEnabled(fromOptions.getInternalToolExecutionEnabled())
			.toolCallbacks(fromOptions.getToolCallbacks())
			.toolNames(fromOptions.getToolNames())
			.toolContext(fromOptions.getToolContext())
			.build();
	}

	/**
	 * Build a map of Hermes-specific HTTP header values from these options.
	 */
	public Map<String, String> toHttpHeaders() {
		Map<String, String> headers = new HashMap<>();
		if (xOpenclawModel != null && !xOpenclawModel.isEmpty()) {
			headers.put("x-hermes-model", xOpenclawModel);
		}
		if (xOpenclawSessionKey != null && !xOpenclawSessionKey.isEmpty()) {
			headers.put("x-hermes-session-key", xOpenclawSessionKey);
		}
		if (xOpenclawMessageChannel != null && !xOpenclawMessageChannel.isEmpty()) {
			headers.put("x-hermes-message-channel", xOpenclawMessageChannel);
		}
		if (xOpenclawAgentId != null && !xOpenclawAgentId.isEmpty()) {
			headers.put("x-hermes-agent-id", xOpenclawAgentId);
		}
		return headers;
	}

	/**
	 * Convert to a {@link Map} of key/value pairs (for JSON serialization).
	 */
	public Map<String, Object> toMap() {
		return ModelOptionsUtils.objectToMap(this);
	}

	@Override
	public HermesChatOptions copy() {
		return fromOptions(this);
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	@Override
	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@Override
	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	@Override
	public Double getTopP() {
		return this.topP;
	}

	public void setTopP(Double topP) {
		this.topP = topP;
	}

	@Override
	public Integer getTopK() {
		return this.topK;
	}

	public void setTopK(Integer topK) {
		this.topK = topK;
	}

	@Override
	public Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	public void setFrequencyPenalty(Double frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	@Override
	public Double getPresencePenalty() {
		return this.presencePenalty;
	}

	public void setPresencePenalty(Double presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	public Integer getSeed() {
		return this.seed;
	}

	public void setSeed(Integer seed) {
		this.seed = seed;
	}

	@Override
	@JsonIgnore
	public List<String> getStopSequences() {
		return getStop();
	}

	@JsonIgnore
	public void setStopSequences(List<String> stopSequences) {
		setStop(stopSequences);
	}

	public List<String> getStop() {
		return this.stop;
	}

	public void setStop(List<String> stop) {
		this.stop = stop;
	}

	@Override
	@JsonIgnore
	public Integer getMaxTokens() {
		return this.maxTokens;
	}

	@JsonIgnore
	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	public String getUser() {
		return this.user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getXOpenclawModel() {
		return this.xOpenclawModel;
	}

	public void setXOpenclawModel(String xOpenclawModel) {
		this.xOpenclawModel = xOpenclawModel;
	}

	public String getXOpenclawSessionKey() {
		return this.xOpenclawSessionKey;
	}

	public void setXOpenclawSessionKey(String xOpenclawSessionKey) {
		this.xOpenclawSessionKey = xOpenclawSessionKey;
	}

	public String getXOpenclawMessageChannel() {
		return this.xOpenclawMessageChannel;
	}

	public void setXOpenclawMessageChannel(String xOpenclawMessageChannel) {
		this.xOpenclawMessageChannel = xOpenclawMessageChannel;
	}

	public String getXOpenclawAgentId() {
		return this.xOpenclawAgentId;
	}

	public void setXOpenclawAgentId(String xOpenclawAgentId) {
		this.xOpenclawAgentId = xOpenclawAgentId;
	}

	@Override
	@Nullable
	@JsonIgnore
	public Boolean getInternalToolExecutionEnabled() {
		return this.internalToolExecutionEnabled;
	}

	@Override
	@JsonIgnore
	public void setInternalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
		this.internalToolExecutionEnabled = internalToolExecutionEnabled;
	}

	@Override
	@JsonIgnore
	public List<ToolCallback> getToolCallbacks() {
		return this.toolCallbacks;
	}

	@Override
	@JsonIgnore
	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
		Assert.noNullElements(toolCallbacks, "toolCallbacks cannot contain null elements");
		this.toolCallbacks = toolCallbacks;
	}

	@Override
	@JsonIgnore
	public Set<String> getToolNames() {
		return this.toolNames;
	}

	@Override
	@JsonIgnore
	public void setToolNames(Set<String> toolNames) {
		Assert.notNull(toolNames, "toolNames cannot be null");
		Assert.noNullElements(toolNames, "toolNames cannot contain null elements");
		toolNames.forEach(tool -> Assert.hasText(tool, "toolNames cannot contain empty elements"));
		this.toolNames = toolNames;
	}

	@Override
	@Nullable
	@JsonIgnore
	public Map<String, Object> getToolContext() {
		return this.toolContext;
	}

	@Override
	@JsonIgnore
	public void setToolContext(Map<String, Object> toolContext) {
		this.toolContext = toolContext;
	}

	@Override
	@JsonIgnore
	public String getOutputSchema() {
		if (this.format == null) {
			return null;
		}
		if (this.format instanceof String) {
			return (String) this.format;
		}
		return ModelOptionsUtils.toJsonString(this.format);
	}

	@Override
	@JsonIgnore
	public void setOutputSchema(String outputSchema) {
		if (outputSchema != null) {
			this.format = ModelOptionsUtils.jsonToMap(outputSchema);
		}
		else {
			this.format = null;
		}
	}

	// -----------------------------------------------------------------------
	// equals / hashCode
	// -----------------------------------------------------------------------

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		HermesChatOptions that = (HermesChatOptions) o;
		return Objects.equals(this.model, that.model)
			&& Objects.equals(this.temperature, that.temperature)
			&& Objects.equals(this.topP, that.topP)
			&& Objects.equals(this.topK, that.topK)
			&& Objects.equals(this.frequencyPenalty, that.frequencyPenalty)
			&& Objects.equals(this.presencePenalty, that.presencePenalty)
			&& Objects.equals(this.seed, that.seed)
			&& Objects.equals(this.stop, that.stop)
			&& Objects.equals(this.maxTokens, that.maxTokens)
			&& Objects.equals(this.user, that.user)
			&& Objects.equals(this.xOpenclawModel, that.xOpenclawModel)
			&& Objects.equals(this.xOpenclawSessionKey, that.xOpenclawSessionKey)
			&& Objects.equals(this.xOpenclawMessageChannel, that.xOpenclawMessageChannel)
			&& Objects.equals(this.xOpenclawAgentId, that.xOpenclawAgentId)
			&& Objects.equals(this.format, that.format)
			&& Objects.equals(this.toolCallbacks, that.toolCallbacks)
			&& Objects.equals(this.toolNames, that.toolNames)
			&& Objects.equals(this.toolContext, that.toolContext)
			&& Objects.equals(this.internalToolExecutionEnabled, that.internalToolExecutionEnabled);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.model, this.temperature, this.topP, this.topK,
			this.frequencyPenalty, this.presencePenalty, this.seed, this.stop,
			this.maxTokens, this.user, this.xOpenclawModel, this.xOpenclawSessionKey,
			this.xOpenclawMessageChannel, this.xOpenclawAgentId, this.format,
			this.toolCallbacks, this.toolNames, this.toolContext,
			this.internalToolExecutionEnabled);
	}

	// -----------------------------------------------------------------------
	// Builder
	// -----------------------------------------------------------------------

	public static final class Builder {

		private final HermesChatOptions options = new HermesChatOptions();

		public Builder model(String model) {
			this.options.model = model;
			return this;
		}

		public Builder model(HermesModel model) {
			this.options.model = model.getName();
			return this;
		}

		public Builder temperature(Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		public Builder topP(Double topP) {
			this.options.topP = topP;
			return this;
		}

		public Builder topK(Integer topK) {
			this.options.topK = topK;
			return this;
		}

		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		public Builder presencePenalty(Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		public Builder seed(Integer seed) {
			this.options.seed = seed;
			return this;
		}

		public Builder stop(List<String> stop) {
			this.options.stop = stop;
			return this;
		}

		public Builder maxTokens(Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		public Builder user(String user) {
			this.options.user = user;
			return this;
		}

		/**
		 * Override the backend provider/model for the selected agent.
		 * Sent as the {@code x-hermes-model} HTTP header.
		 * Example: "openai/gpt-5.4" or "gpt-5.5".
		 */
		public Builder xOpenclawModel(String xOpenclawModel) {
			this.options.xOpenclawModel = xOpenclawModel;
			return this;
		}

		/**
		 * Explicit session routing key.
		 * Sent as the {@code x-hermes-session-key} HTTP header.
		 */
		public Builder xOpenclawSessionKey(String xOpenclawSessionKey) {
			this.options.xOpenclawSessionKey = xOpenclawSessionKey;
			return this;
		}

		/**
		 * Synthetic ingress channel context (e.g. "slack", "discord").
		 * Sent as the {@code x-hermes-message-channel} HTTP header.
		 */
		public Builder xOpenclawMessageChannel(String xOpenclawMessageChannel) {
			this.options.xOpenclawMessageChannel = xOpenclawMessageChannel;
			return this;
		}

		/**
		 * Compatibility agent-id override.
		 * Sent as the {@code x-hermes-agent-id} HTTP header.
		 */
		public Builder xOpenclawAgentId(String xOpenclawAgentId) {
			this.options.xOpenclawAgentId = xOpenclawAgentId;
			return this;
		}

		public Builder outputSchema(String outputSchema) {
			this.options.setOutputSchema(outputSchema);
			return this;
		}

		public Builder internalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
			this.options.setInternalToolExecutionEnabled(internalToolExecutionEnabled);
			return this;
		}

		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
			this.options.setToolCallbacks(toolCallbacks);
			return this;
		}

		public Builder toolCallbacks(ToolCallback... toolCallbacks) {
			Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
			this.options.toolCallbacks.addAll(Arrays.asList(toolCallbacks));
			return this;
		}

		public Builder toolNames(Set<String> toolNames) {
			this.options.setToolNames(toolNames);
			return this;
		}

		public Builder toolNames(String... toolNames) {
			Assert.notNull(toolNames, "toolNames cannot be null");
			this.options.toolNames.addAll(Set.of(toolNames));
			return this;
		}

		public Builder toolContext(Map<String, Object> toolContext) {
			if (this.options.toolContext == null) {
				this.options.toolContext = toolContext;
			}
			else {
				this.options.toolContext.putAll(toolContext);
			}
			return this;
		}

		public HermesChatOptions build() {
			return this.options;
		}
	}
}
