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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.ai.util.ResourceUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HermesChatOptions}.
 *
 * @author Loong Wan
 */
public class HermesChatOptionsTests {

	@Test
	void testBasicOptions() {
		var options = HermesChatOptions.builder()
				.temperature(3.14).topK(30).stop(List.of("a", "b", "c")).build();

		var optionsMap = options.toMap();
		assertThat(optionsMap).containsEntry("temperature", 3.14);
		assertThat(optionsMap).containsEntry("stop", List.of("a", "b", "c"));
		// topK is @JsonIgnore — not in serialized map
		assertThat(options.getTopK()).isEqualTo(30);
	}

	@Test
	void testStandardOpenAIFields() {
		var options = HermesChatOptions.builder()
			.model("hermes/default")
			.temperature(0.7)
			.topP(0.9)
			.frequencyPenalty(0.5)
			.presencePenalty(0.3)
			.seed(42)
			.stop(List.of("END"))
			.maxTokens(2048)
			.user("conv:abc123")
			.build();

		var optionsMap = options.toMap();
		assertThat(optionsMap).containsEntry("model", "hermes/default");
		assertThat(optionsMap).containsEntry("temperature", 0.7);
		assertThat(optionsMap).containsEntry("top_p", 0.9);
		assertThat(optionsMap).containsEntry("frequency_penalty", 0.5);
		assertThat(optionsMap).containsEntry("presence_penalty", 0.3);
		assertThat(optionsMap).containsEntry("seed", 42);
		assertThat(optionsMap).containsEntry("stop", List.of("END"));
		assertThat(optionsMap).containsEntry("max_tokens", 2048);
		assertThat(optionsMap).containsEntry("user", "conv:abc123");
	}

	@Test
	void testXOpenclawHeadersNotInMap() {
		var options = HermesChatOptions.builder()
			.model("hermes/default")
			.xOpenclawModel("openai/gpt-5.4")
			.xOpenclawSessionKey("my-session-key")
			.xOpenclawMessageChannel("slack")
			.xOpenclawAgentId("my-agent")
			.build();

		// These should NOT appear in the serialized JSON body map
		var optionsMap = options.toMap();
		assertThat(optionsMap).doesNotContainKey("x_hermes_model");
		assertThat(optionsMap).doesNotContainKey("xOpenclawModel");
		assertThat(optionsMap).doesNotContainKey("x_hermes_session_key");
		assertThat(optionsMap).doesNotContainKey("xOpenclawSessionKey");

		// But they ARE accessible via getters
		assertThat(options.getXOpenclawModel()).isEqualTo("openai/gpt-5.4");
		assertThat(options.getXOpenclawSessionKey()).isEqualTo("my-session-key");
		assertThat(options.getXOpenclawMessageChannel()).isEqualTo("slack");
		assertThat(options.getXOpenclawAgentId()).isEqualTo("my-agent");

		// toHttpHeaders() should convert them to HTTP header names
		var headers = options.toHttpHeaders();
		assertThat(headers).containsEntry("x-hermes-model", "openai/gpt-5.4");
		assertThat(headers).containsEntry("x-hermes-session-key", "my-session-key");
		assertThat(headers).containsEntry("x-hermes-message-channel", "slack");
		assertThat(headers).containsEntry("x-hermes-agent-id", "my-agent");
	}

	@Test
	void testToHttpHeadersSkipsNullAndEmpty() {
		var options = HermesChatOptions.builder()
			.model("hermes/default")
			.xOpenclawModel("openai/gpt-5.4")
			.build();

		var headers = options.toHttpHeaders();
		assertThat(headers).hasSize(1);
		assertThat(headers).containsEntry("x-hermes-model", "openai/gpt-5.4");
		assertThat(headers).doesNotContainKey("x-hermes-session-key");
	}

	@Test
	void testOutputSchemaOptionWithJsonSchemaObjectAsString() {
		var jsonSchemaAsText = ResourceUtils.getText("classpath:country-json-schema.json");
		var options = HermesChatOptions.builder().outputSchema(jsonSchemaAsText).build();

		assertThat(options.getOutputSchema()).isEqualToIgnoringWhitespace(jsonSchemaAsText);
	}

	@Test
	void testFunctionAndToolOptions() {
		var options = HermesChatOptions.builder()
			.toolNames("function1")
			.toolNames("function2")
			.toolNames("function3")
			.toolContext(Map.of("key1", "value1", "key2", "value2"))
			.build();

		// Function-related fields are not included in the map due to @JsonIgnore
		var optionsMap = options.toMap();
		assertThat(optionsMap).doesNotContainKey("functions");
		assertThat(optionsMap).doesNotContainKey("tool_context");

		// But they are accessible through getters
		assertThat(options.getToolNames()).containsExactlyInAnyOrder("function1", "function2", "function3");
		assertThat(options.getToolContext())
			.containsExactlyInAnyOrderEntriesOf(Map.of("key1", "value1", "key2", "value2"));
	}

	@Test
	void testFunctionOptionsWithMutableSet() {
		Set<String> functionSet = new HashSet<>();
		functionSet.add("function1");
		functionSet.add("function2");

		var options = HermesChatOptions.builder().toolNames(functionSet).toolNames("function3").build();

		assertThat(options.getToolNames()).containsExactlyInAnyOrder("function1", "function2", "function3");
	}

	@Test
	void testFromOptions() {
		var originalOptions = HermesChatOptions.builder()
			.model("hermes/default")
			.temperature(0.7)
			.topK(40)
			.xOpenclawModel("openai/gpt-5.4")
			.user("conv:abc")
			.toolNames(Set.of("function1"))
			.build();

		var copiedOptions = HermesChatOptions.fromOptions(originalOptions);

		assertThat(copiedOptions.getModel()).isEqualTo("hermes/default");
		assertThat(copiedOptions.getTemperature()).isEqualTo(0.7);
		assertThat(copiedOptions.getTopK()).isEqualTo(40);
		assertThat(copiedOptions.getXOpenclawModel()).isEqualTo("openai/gpt-5.4");
		assertThat(copiedOptions.getUser()).isEqualTo("conv:abc");
		assertThat(copiedOptions.getToolNames()).containsExactly("function1");
	}

	@Test
	void testEmptyOptions() {
		var options = HermesChatOptions.builder().build();

		var optionsMap = options.toMap();
		assertThat(optionsMap).isEmpty();

		assertThat(options.getModel()).isNull();
		assertThat(options.getTemperature()).isNull();
		assertThat(options.getTopK()).isNull();
		assertThat(options.getToolNames()).isEmpty();
		assertThat(options.getToolContext()).isEmpty();
		assertThat(options.getXOpenclawModel()).isNull();
		assertThat(options.getUser()).isNull();
	}

	@Test
	void testNullValuesNotIncludedInMap() {
		var options = HermesChatOptions.builder()
				.model("hermes/default").temperature(null).topK(null).stop(null).build();

		var optionsMap = options.toMap();
		assertThat(optionsMap).containsEntry("model", "hermes/default");
		assertThat(optionsMap).doesNotContainKey("temperature");
		assertThat(optionsMap).doesNotContainKey("top_k");
		assertThat(optionsMap).doesNotContainKey("stop");
	}

	@Test
	void testZeroValuesIncludedInMap() {
		var options = HermesChatOptions.builder().temperature(0.0).topK(0).seed(0).build();

		var optionsMap = options.toMap();
		assertThat(optionsMap).containsEntry("temperature", 0.0);
		assertThat(optionsMap).containsEntry("seed", 0);
		// topK is @JsonIgnore
	}
}
