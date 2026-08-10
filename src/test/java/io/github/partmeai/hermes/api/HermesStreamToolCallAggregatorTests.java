package io.github.partmeai.hermes.api;

import java.util.List;

import io.github.partmeai.hermes.api.HermesApi.ChatResponse;
import io.github.partmeai.hermes.api.HermesApi.Message;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class HermesStreamToolCallAggregatorTests {

	@Test
	void shouldMergeFragmentsIntoOneTerminalToolCall() {
		ChatResponse first = chunk(new Message.ToolCall(0, "call-1", "function",
				new Message.ToolCallFunction("weather", "{\"city\":\"")), null);
		ChatResponse second = chunk(new Message.ToolCall(0, null, null,
				new Message.ToolCallFunction(null, "Paris\"}")), null);
		ChatResponse terminal = chunk(null, "tool_calls");

		List<ChatResponse> merged = new HermesStreamToolCallAggregator()
			.aggregate(Flux.just(first, second, terminal))
			.collectList()
			.block();

		assertThat(merged).hasSize(1);
		Message.ToolCall toolCall = merged.get(0).choices().get(0).delta().toolCalls().get(0);
		assertThat(toolCall.id()).isEqualTo("call-1");
		assertThat(toolCall.function().name()).isEqualTo("weather");
		assertThat(toolCall.function().arguments()).isEqualTo("{\"city\":\"Paris\"}");
		assertThat(merged.get(0).choices().get(0).finishReason()).isEqualTo("tool_calls");
	}

	private ChatResponse chunk(Message.ToolCall toolCall, String finishReason) {
		List<Message.ToolCall> toolCalls = toolCall == null ? null : List.of(toolCall);
		Message delta = new Message(Message.Role.ASSISTANT, null, toolCalls, null, null);
		ChatResponse.Choice choice = new ChatResponse.Choice(0, null, delta, finishReason);
		return new ChatResponse("chat-1", "chat.completion.chunk", 1L,
				"hermes-agent", List.of(choice), null);
	}
}
