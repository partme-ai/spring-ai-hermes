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

package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesChatOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link HermesChatModel} chat request construction.
 *
 * @author Loong Wan
 */
public class HermesChatRequestTests {

	private final HermesChatModel chatModel = HermesChatModel.builder()
		.api(HermesApi.builder().build())
		.defaultOptions(HermesChatOptions.builder().model("MODEL_NAME").temperature(66.6).topK(99).build())
		.retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
		.build();

	@Test
	void whenToolRuntimeOptionsThenMergeWithDefaults() {
		HermesChatOptions defaultOptions = HermesChatOptions.builder()
			.model("MODEL_NAME")
			.internalToolExecutionEnabled(true)
			.toolCallbacks(new TestToolCallback("tool1"), new TestToolCallback("tool2"))
			.toolNames("tool1", "tool2")
			.toolContext(Map.of("key1", "value1", "key2", "valueA"))
			.build();
		HermesChatModel chatModel = HermesChatModel.builder()
			.api(HermesApi.builder().build())
			.defaultOptions(defaultOptions)
			.build();

		HermesChatOptions runtimeOptions = HermesChatOptions.builder()
			.internalToolExecutionEnabled(false)
			.toolCallbacks(new TestToolCallback("tool3"), new TestToolCallback("tool4"))
			.toolNames("tool3")
			.toolContext(Map.of("key2", "valueB"))
			.build();
		Prompt prompt = chatModel.buildRequestPrompt(new Prompt("Test message content", runtimeOptions));

		assertThat(((ToolCallingChatOptions) prompt.getOptions())).isNotNull();
		assertThat(((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
		assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).hasSize(2);
		assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()
			.stream()
			.map(toolCallback -> toolCallback.getToolDefinition().name())).containsExactlyInAnyOrder("tool3", "tool4");
		assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolNames()).containsExactlyInAnyOrder("tool3");
		assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolContext()).containsEntry("key1", "value1")
			.containsEntry("key2", "valueB");
	}

	@Test
	void createRequestWithDefaultOptions() {
		var prompt = this.chatModel.buildRequestPrompt(new Prompt("Test message content"));

		var request = this.chatModel.hermesChatRequest(prompt, false);

		assertThat(request.messages()).hasSize(1);
		assertThat(request.stream()).isFalse();
		assertThat(request.model()).isEqualTo("MODEL_NAME");
		assertThat(request.temperature()).isEqualTo(66.6);
		assertThat(request.topP()).isNull();
	}

	@Test
	void createRequestWithPromptHermesOptions() {
		HermesChatOptions promptOptions = HermesChatOptions.builder().temperature(0.8).topP(0.5).build();
		var prompt = this.chatModel.buildRequestPrompt(new Prompt("Test message content", promptOptions));

		var request = this.chatModel.hermesChatRequest(prompt, true);

		assertThat(request.messages()).hasSize(1);
		assertThat(request.stream()).isTrue();
		assertThat(request.model()).isEqualTo("MODEL_NAME");
		assertThat(request.temperature()).isEqualTo(0.8);
		assertThat(request.topP()).isEqualTo(0.5);
	}

	@Test
	public void createRequestWithPromptPortableChatOptions() {
		ChatOptions portablePromptOptions = ChatOptions.builder().temperature(0.9).topK(100).topP(0.6).build();
		var prompt = this.chatModel.buildRequestPrompt(new Prompt("Test message content", portablePromptOptions));

		var request = this.chatModel.hermesChatRequest(prompt, true);

		assertThat(request.messages()).hasSize(1);
		assertThat(request.stream()).isTrue();
		assertThat(request.model()).isEqualTo("MODEL_NAME");
		assertThat(request.temperature()).isEqualTo(0.9);
		assertThat(request.topP()).isEqualTo(0.6);
	}

	@Test
	public void createRequestWithPromptOptionsModelOverride() {
		HermesChatOptions promptOptions = HermesChatOptions.builder().model("PROMPT_MODEL").build();
		var prompt = this.chatModel.buildRequestPrompt(new Prompt("Test message content", promptOptions));

		var request = this.chatModel.hermesChatRequest(prompt, true);

		assertThat(request.model()).isEqualTo("PROMPT_MODEL");
	}

	@Test
	public void createRequestWithDefaultOptionsModelOverride() {
		HermesChatModel chatModel = HermesChatModel.builder()
			.api(HermesApi.builder().build())
			.defaultOptions(HermesChatOptions.builder().model("DEFAULT_OPTIONS_MODEL").build())
			.retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
			.build();

		var prompt1 = chatModel.buildRequestPrompt(new Prompt("Test message content"));
		var request = chatModel.hermesChatRequest(prompt1, true);
		assertThat(request.model()).isEqualTo("DEFAULT_OPTIONS_MODEL");

		HermesChatOptions promptOptions = HermesChatOptions.builder().model("PROMPT_MODEL").build();
		var prompt2 = chatModel.buildRequestPrompt(new Prompt("Test message content", promptOptions));
		request = chatModel.hermesChatRequest(prompt2, true);
		assertThat(request.model()).isEqualTo("PROMPT_MODEL");
	}

	@Test
	void createRequestWithAllMessageTypes() {
		var prompt = this.chatModel.buildRequestPrompt(new Prompt(createMessagesWithAllMessageTypes()));

		var request = this.chatModel.hermesChatRequest(prompt, false);

		assertThat(request.messages()).hasSize(6);

		var systemMessage = request.messages().get(0);
		assertThat(systemMessage.role()).isEqualTo(HermesApi.Message.Role.SYSTEM);
		assertThat(systemMessage.content()).isEqualTo("Test system message");

		var userMessage = request.messages().get(1);
		assertThat(userMessage.role()).isEqualTo(HermesApi.Message.Role.USER);
		assertThat(userMessage.content()).isEqualTo("Test user message");

		var toolResponse1 = request.messages().get(2);
		assertThat(toolResponse1.role()).isEqualTo(HermesApi.Message.Role.TOOL);
		assertThat(toolResponse1.content()).isEqualTo("Test tool response 1");

		var toolResponse2 = request.messages().get(3);
		assertThat(toolResponse2.role()).isEqualTo(HermesApi.Message.Role.TOOL);
		assertThat(toolResponse2.content()).isEqualTo("Test tool response 2");

		var toolResponse3 = request.messages().get(4);
		assertThat(toolResponse3.role()).isEqualTo(HermesApi.Message.Role.TOOL);
		assertThat(toolResponse3.content()).isEqualTo("Test tool response 3");

		var assistantMessage = request.messages().get(5);
		assertThat(assistantMessage.role()).isEqualTo(HermesApi.Message.Role.ASSISTANT);
		assertThat(assistantMessage.content()).isEqualTo("Test assistant message");
	}

	@Test
	void createRequestWithUserAndXOpenclawHeaders() {
		HermesChatOptions options = HermesChatOptions.builder()
			.model("hermes-agent")
			.user("conv:my-conversation")
			.hermesSessionKey("openai/gpt-5.4")
			.hermesSessionId("slack")
			.build();

		var prompt = this.chatModel.buildRequestPrompt(new Prompt("Test", options));
		var request = this.chatModel.hermesChatRequest(prompt, false);

		assertThat(request.user()).isEqualTo("conv:my-conversation");
		assertThat(request.model()).isEqualTo("hermes-agent");

		// Headers should be in the options, not the request body
		assertThat(options.getHermesSessionKey()).isEqualTo("openai/gpt-5.4");
		assertThat(options.getHermesSessionId()).isEqualTo("slack");
	}

	@Test
	void createRequestWithoutModelThrowsException() {
		HermesChatModel model = HermesChatModel.builder()
			.api(HermesApi.builder().build())
			.defaultOptions(HermesChatOptions.builder().build())
			.retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
			.build();

		assertThatThrownBy(() -> model.buildRequestPrompt(new Prompt("Test")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("model cannot be null or empty");
	}

	private static List<Message> createMessagesWithAllMessageTypes() {
		var systemMessage = new SystemMessage("Test system message");
		var userMessage = new UserMessage("Test user message");
		var toolResponseMessage = ToolResponseMessage.builder().responses(List.of(
				new ToolResponse("tool1", "Tool 1", "Test tool response 1"),
				new ToolResponse("tool2", "Tool 2", "Test tool response 2"),
				new ToolResponse("tool3", "Tool 3", "Test tool response 3"))).build();
		var assistantMessage = new AssistantMessage("Test assistant message");

		return List.of(systemMessage, userMessage, toolResponseMessage, assistantMessage);
	}

	static class TestToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		TestToolCallback(String name) {
			this.toolDefinition = DefaultToolDefinition.builder().name(name).inputSchema("{}").build();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return this.toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return "Mission accomplished!";
		}
	}
}
