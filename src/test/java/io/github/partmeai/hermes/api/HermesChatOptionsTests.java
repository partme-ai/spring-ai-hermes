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

public class HermesChatOptionsTests {

	@Test
	void testBasicOptions() {
		var options = HermesChatOptions.builder().temperature(3.14).topK(30).stop(List.of("a", "b", "c")).build();
		var m = options.toMap();
		assertThat(m).containsEntry("temperature", 3.14);
		assertThat(m).containsEntry("stop", List.of("a", "b", "c"));
		assertThat(options.getTopK()).isEqualTo(30);
	}

	@Test
	void testStandardOpenAIFields() {
		var options = HermesChatOptions.builder().model("hermes-agent").temperature(0.7).topP(0.9)
				.frequencyPenalty(0.5).presencePenalty(0.3).seed(42).stop(List.of("END"))
				.maxTokens(2048).user("conv:abc123").build();
		var m = options.toMap();
		assertThat(m).containsEntry("model", "hermes-agent");
		assertThat(m).containsEntry("temperature", 0.7);
		assertThat(m).containsEntry("top_p", 0.9);
		assertThat(m).containsEntry("frequency_penalty", 0.5);
		assertThat(m).containsEntry("presence_penalty", 0.3);
		assertThat(m).containsEntry("seed", 42);
		assertThat(m).containsEntry("max_tokens", 2048);
		assertThat(m).containsEntry("user", "conv:abc123");
	}

	@Test
	void testHermesHeadersNotInMap() {
		var options = HermesChatOptions.builder().model("hermes-agent")
				.hermesSessionKey("agent:main:webui:dm:user-42")
				.hermesSessionId("transcript-alpha").build();
		var m = options.toMap();
		assertThat(m).doesNotContainKey("hermesSessionKey");
		assertThat(m).doesNotContainKey("hermesSessionId");
		assertThat(options.getHermesSessionKey()).isEqualTo("agent:main:webui:dm:user-42");
		assertThat(options.getHermesSessionId()).isEqualTo("transcript-alpha");
		var headers = options.toHttpHeaders();
		assertThat(headers).containsEntry("X-Hermes-Session-Key", "agent:main:webui:dm:user-42");
		assertThat(headers).containsEntry("X-Hermes-Session-Id", "transcript-alpha");
	}

	@Test
	void testToHttpHeadersSkipsNullAndEmpty() {
		var options = HermesChatOptions.builder().model("hermes-agent")
				.hermesSessionKey("key-1").build();
		var headers = options.toHttpHeaders();
		assertThat(headers).hasSize(1);
		assertThat(headers).containsEntry("X-Hermes-Session-Key", "key-1");
		assertThat(headers).doesNotContainKey("X-Hermes-Session-Id");
	}

	@Test
	void testOutputSchemaOptionWithJsonSchemaObjectAsString() {
		var jsonSchemaAsText = ResourceUtils.getText("classpath:country-json-schema.json");
		var options = HermesChatOptions.builder().outputSchema(jsonSchemaAsText).build();
		assertThat(options.getOutputSchema()).isEqualToIgnoringWhitespace(jsonSchemaAsText);
	}

	@Test
	void testFunctionAndToolOptions() {
		var options = HermesChatOptions.builder().toolNames("function1").toolNames("function2").toolNames("function3")
				.toolContext(Map.of("key1", "value1", "key2", "value2")).build();
		var m = options.toMap();
		assertThat(m).doesNotContainKey("functions");
		assertThat(m).doesNotContainKey("tool_context");
		assertThat(options.getToolNames()).containsExactlyInAnyOrder("function1", "function2", "function3");
		assertThat(options.getToolContext()).containsExactlyInAnyOrderEntriesOf(Map.of("key1", "value1", "key2", "value2"));
	}

	@Test
	void testFunctionOptionsWithMutableSet() {
		Set<String> functionSet = new HashSet<>();
		functionSet.add("function1"); functionSet.add("function2");
		var options = HermesChatOptions.builder().toolNames(functionSet).toolNames("function3").build();
		assertThat(options.getToolNames()).containsExactlyInAnyOrder("function1", "function2", "function3");
	}

	@Test
	void testFromOptions() {
		var original = HermesChatOptions.builder().model("hermes-agent").temperature(0.7).topK(40)
				.hermesSessionKey("sk").user("conv:abc").toolNames(Set.of("function1")).build();
		var copied = HermesChatOptions.fromOptions(original);
		assertThat(copied.getModel()).isEqualTo("hermes-agent");
		assertThat(copied.getTemperature()).isEqualTo(0.7);
		assertThat(copied.getTopK()).isEqualTo(40);
		assertThat(copied.getHermesSessionKey()).isEqualTo("sk");
		assertThat(copied.getUser()).isEqualTo("conv:abc");
		assertThat(copied.getToolNames()).containsExactly("function1");
	}

	@Test
	void testEmptyOptions() {
		var options = HermesChatOptions.builder().build();
		var m = options.toMap();
		assertThat(m).isEmpty();
		assertThat(options.getModel()).isNull();
		assertThat(options.getTemperature()).isNull();
		assertThat(options.getHermesSessionKey()).isNull();
		assertThat(options.getUser()).isNull();
	}

	@Test
	void testNullValuesNotIncludedInMap() {
		var options = HermesChatOptions.builder().model("hermes-agent").temperature(null).topK(null).stop(null).build();
		var m = options.toMap();
		assertThat(m).containsEntry("model", "hermes-agent");
		assertThat(m).doesNotContainKey("temperature");
		assertThat(m).doesNotContainKey("top_k");
		assertThat(m).doesNotContainKey("stop");
	}

	@Test
	void testZeroValuesIncludedInMap() {
		var options = HermesChatOptions.builder().temperature(0.0).topK(0).seed(0).build();
		var m = options.toMap();
		assertThat(m).containsEntry("temperature", 0.0);
		assertThat(m).containsEntry("seed", 0);
	}
}
