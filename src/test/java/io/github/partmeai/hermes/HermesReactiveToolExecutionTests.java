package io.github.partmeai.hermes;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesChatOptions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class HermesReactiveToolExecutionTests {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws Exception {
		this.server = new MockWebServer();
		this.server.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		this.server.shutdown();
	}

	@Test
	void shouldExecuteFragmentedStreamingToolCallExactlyOnce() {
		this.server.enqueue(new MockResponse()
			.setHeader("Content-Type", "text/event-stream")
			.setBody(toolCallStream()));
		AtomicInteger executions = new AtomicInteger();
		AtomicReference<ChatResponse> executedResponse = new AtomicReference<>();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "sunny")))
			.build();
		ToolExecutionResult toolExecutionResult = ToolExecutionResult.builder()
			.conversationHistory(List.of(toolResponse))
			.returnDirect(true)
			.build();
		ToolCallingManager toolCallingManager = new ToolCallingManager() {
			@Override
			public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
				return List.of();
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
				executions.incrementAndGet();
				executedResponse.set(response);
				return toolExecutionResult;
			}
		};
		ToolExecutionEligibilityPredicate predicate = (options, response) ->
				!response.getResult().getOutput().getToolCalls().isEmpty();

		HermesApi api = HermesApi.builder().baseUrl(this.server.url("/").toString()).build();
		HermesChatModel model = HermesChatModel.builder()
			.api(api)
			.defaultOptions(HermesChatOptions.builder().model("hermes-agent")
				.internalToolExecutionEnabled(true).build())
			.toolCallingManager(toolCallingManager)
			.toolExecutionEligibilityPredicate(predicate)
			.build();

		List<ChatResponse> responses = model.stream(new Prompt("weather"))
			.collectList().block(Duration.ofSeconds(10));

		assertThat(responses).isNotEmpty();
		assertThat(executions).hasValue(1);
		var toolCall = executedResponse.get().getResult().getOutput().getToolCalls().get(0);
		assertThat(toolCall.id()).isEqualTo("call-1");
		assertThat(toolCall.name()).isEqualTo("weather");
		assertThat(toolCall.arguments()).isEqualTo("{\"city\":\"Paris\"}");
	}

	private String toolCallStream() {
		HermesApi.ChatResponse first = chunk(new HermesApi.Message.ToolCall(0, "call-1", "function",
				new HermesApi.Message.ToolCallFunction("weather", "{\"city\":\"")), null);
		HermesApi.ChatResponse second = chunk(new HermesApi.Message.ToolCall(0, null, null,
				new HermesApi.Message.ToolCallFunction(null, "Paris\"}")), null);
		HermesApi.ChatResponse terminal = chunk(null, "tool_calls");
		return "data: " + ModelOptionsUtils.toJsonString(first) + "\n\n"
				+ "data: " + ModelOptionsUtils.toJsonString(second) + "\n\n"
				+ "data: " + ModelOptionsUtils.toJsonString(terminal) + "\n\n"
				+ "data: [DONE]\n\n";
	}

	private HermesApi.ChatResponse chunk(HermesApi.Message.ToolCall toolCall, String finishReason) {
		List<HermesApi.Message.ToolCall> toolCalls = toolCall == null ? null : List.of(toolCall);
		HermesApi.Message delta = new HermesApi.Message(HermesApi.Message.Role.ASSISTANT,
				null, toolCalls, null, null);
		HermesApi.ChatResponse.Choice choice = new HermesApi.ChatResponse.Choice(
				0, null, delta, finishReason);
		return new HermesApi.ChatResponse("chat-1", "chat.completion.chunk", 1L,
				"hermes-agent", List.of(choice), null);
	}
}
