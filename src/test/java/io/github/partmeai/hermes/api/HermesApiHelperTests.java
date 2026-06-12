package io.github.partmeai.hermes.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HermesApiHelper}.
 */
public class HermesApiHelperTests {

	@Test
	void isStreamingToolCallWhenToolCallsInDeltaShouldReturnTrue() {
		var toolCall = new HermesApi.Message.ToolCall("call-1", "function",
				new HermesApi.Message.ToolCallFunction("test_fn", "{}"));
		var delta = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.toolCalls(List.of(toolCall)).build();
		var choice = new HermesApi.ChatResponse.Choice(0, null, delta, null);
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.isStreamingToolCall(response)).isTrue();
	}

	@Test
	void isStreamingToolCallWhenNoToolCallsShouldReturnFalse() {
		var delta = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.content("hello").build();
		var choice = new HermesApi.ChatResponse.Choice(0, null, delta, null);
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.isStreamingToolCall(response)).isFalse();
	}

	@Test
	void isStreamingToolCallWhenResponseIsNullShouldReturnFalse() {
		assertThat(HermesApiHelper.isStreamingToolCall(null)).isFalse();
	}

	@Test
	void isStreamingDoneWhenHasFinishReasonShouldReturnTrue() {
		var delta = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT).build();
		var choice = new HermesApi.ChatResponse.Choice(0, null, delta, "stop");
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.isStreamingDone(response)).isTrue();
	}

	@Test
	void isStreamingDoneWhenNoFinishReasonShouldReturnFalse() {
		var delta = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.content("hello").build();
		var choice = new HermesApi.ChatResponse.Choice(0, null, delta, null);
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.isStreamingDone(response)).isFalse();
	}

	@Test
	void isStreamingDoneWhenResponseIsNullShouldReturnFalse() {
		assertThat(HermesApiHelper.isStreamingDone(null)).isFalse();
	}

	@Test
	void getContentFromMessage() {
		var msg = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.content("Hello World").build();
		var choice = new HermesApi.ChatResponse.Choice(0, msg, null, "stop");
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.getContent(response)).isEqualTo("Hello World");
	}

	@Test
	void getContentFromDelta() {
		var delta = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.content("Hello").build();
		var choice = new HermesApi.ChatResponse.Choice(0, null, delta, null);
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "hermes/default", List.of(choice), null);
		assertThat(HermesApiHelper.getContent(response)).isEqualTo("Hello");
	}

	@Test
	void getToolCallsFromMessage() {
		var toolCall = new HermesApi.Message.ToolCall("call-1", "function",
				new HermesApi.Message.ToolCallFunction("test_fn", "{\"arg\":1}"));
		var msg = HermesApi.Message.builder(HermesApi.Message.Role.ASSISTANT)
				.toolCalls(List.of(toolCall)).build();
		var choice = new HermesApi.ChatResponse.Choice(0, msg, null, "tool_calls");
		var response = new HermesApi.ChatResponse("chatcmpl-1", "chat.completion",
				null, "hermes/default", List.of(choice), null);
		var toolCalls = HermesApiHelper.getToolCalls(response);
		assertThat(toolCalls).hasSize(1);
		assertThat(toolCalls.get(0).function().name()).isEqualTo("test_fn");
	}
}
