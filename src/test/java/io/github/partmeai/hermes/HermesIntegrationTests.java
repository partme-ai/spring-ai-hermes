package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;

import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesApi.Message.Role;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a real local Hermes Gateway.
 * Requires: hermes gateway --port 18789 --allow-unconfigured
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HermesIntegrationTests {

	private HermesApi api;

	private HermesChatModel chatModel;

	@BeforeAll
	void setUp() {
		// Use HTTP/1.1 to avoid HTTP/2 upgrade confusion with the Gateway's WS+HTTP multiplex
		var requestFactory = new SimpleClientHttpRequestFactory();
		var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
		var webClientBuilder = WebClient.builder()
			.clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(
				java.net.http.HttpClient.newBuilder()
					.version(java.net.http.HttpClient.Version.HTTP_1_1)
					.build()));

		api = HermesApi.builder()
			.baseUrl("http://127.0.0.1:18789")
			.restClientBuilder(restClientBuilder)
			.webClientBuilder(webClientBuilder)
			.build();
		chatModel = HermesChatModel.builder()
			.hermesApi(api)
			.defaultOptions(HermesChatOptions.builder()
				.model(HermesModel.DEFAULT.id())
				.build())
			.build();
	}

	// ==============================
	// GET /v1/models
	// ==============================

	@Test
	void listModelsShouldReturnAgentTargets() {
		var response = api.listModels();

		assertThat(response).isNotNull();
		assertThat(response.object()).isEqualTo("list");
		assertThat(response.data()).isNotEmpty();

		List<String> ids = response.data().stream().map(HermesApi.ModelData::id).toList();
		assertThat(ids).contains("hermes", "hermes/default");
		assertThat(ids).anyMatch(id -> id.startsWith("hermes/"));
	}

	// ==============================
	// GET /v1/models/{id}
	// ==============================

	@Test
	void getModelShouldReturnAgentTarget() {
		var response = api.getModel("hermes/default");

		assertThat(response).isNotNull();
		assertThat(response.id()).isEqualTo("hermes/default");
		assertThat(response.object()).isEqualTo("model");
	}

	// ==============================
	// POST /v1/chat/completions (non-streaming)
	// ==============================

	@Test
	void chatShouldReturnAssistantMessage() {
		var request = HermesApi.ChatRequest.builder("hermes/default")
			.messages(List.of(HermesApi.Message.builder(Role.USER)
				.content("Say exactly: hello").build()))
			.stream(false)
			.build();

		var response = api.chat(request);

		assertThat(response).isNotNull();
		assertThat(response.id()).isNotEmpty();
		assertThat(response.object()).isEqualTo("chat.completion");
		assertThat(response.model()).isEqualTo("hermes/default");
		assertThat(response.choices()).hasSize(1);
		assertThat(response.choices().get(0).message().role()).isEqualTo(Role.ASSISTANT);
		assertThat(response.choices().get(0).message().content()).containsIgnoringCase("hello");
		assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");

		// Usage should be present
		assertThat(response.usage()).isNotNull();
		assertThat(response.usage().totalTokens()).isPositive();
	}

	// ==============================
	// POST /v1/chat/completions (streaming SSE)
	// ==============================

	@Test
	void streamingChatShouldReturnDeltaChunks() {
		var request = HermesApi.ChatRequest.builder("hermes/default")
			.messages(List.of(HermesApi.Message.builder(Role.USER)
				.content("Say exactly: hi").build()))
			.stream(true)
			.build();

		var flux = api.streamingChat(request);

		var chunks = flux.collectList().block();

		assertThat(chunks).isNotNull().isNotEmpty();

		// Check first chunk has role delta
		var first = chunks.get(0);
		assertThat(first.choices()).hasSize(1);
		assertThat(first.choices().get(0).delta()).isNotNull();

		// Check final chunk has finish_reason
		var last = chunks.get(chunks.size() - 1);
		assertThat(last.choices().get(0).finishReason()).isNotNull();
	}

	// ==============================
	// Spring AI ChatModel integration
	// ==============================

	@Test
	void springAiChatModelCallShouldWork() {
		var response = chatModel.call(
			new org.springframework.ai.chat.prompt.Prompt("Reply in one word: yes"));

		assertThat(response).isNotNull();
		assertThat(response.getResults()).isNotEmpty();
		var text = response.getResult().getOutput().getText();
		assertThat(text).isNotBlank();
		System.out.println("ChatModel response: " + text);
	}

	@Test
	void springAiChatModelStreamShouldWork() {
		var flux = chatModel.stream(
			new org.springframework.ai.chat.prompt.Prompt("Count: 1, 2"));

		var responses = flux.collectList().block();

		assertThat(responses).isNotNull().isNotEmpty();
		// Aggregated response should have content
		var lastResponse = responses.get(responses.size() - 1);
		var text = lastResponse.getResult().getOutput().getText();
		System.out.println("Stream final content: " + text);
	}

	// ==============================
	// x-hermes-* headers
	// ==============================

	@Test
	void chatWithXOpenclawModelHeader() {
		var request = HermesApi.ChatRequest.builder("hermes/default")
			.messages(List.of(HermesApi.Message.builder(Role.USER)
				.content("Say: ok").build()))
			.stream(false)
			.build();

		// Override backend model via HTTP header
		var response = api.chat(request, Map.of("x-hermes-model", "deepseek/deepseek-v4-flash"));

		assertThat(response).isNotNull();
		assertThat(response.choices().get(0).message().content()).containsIgnoringCase("ok");
	}

	// ==============================
	// HermesChatOptions header integration
	// ==============================

	@Test
	void chatOptionsToHttpHeadersShouldMapCorrectly() {
		var options = HermesChatOptions.builder()
			.model("hermes/default")
			.xOpenclawModel("openai/gpt-5.4")
			.xOpenclawSessionKey("test-session")
			.xOpenclawMessageChannel("slack")
			.user("conv:test-123")
			.build();

		var headers = options.toHttpHeaders();
		assertThat(headers).containsEntry("x-hermes-model", "openai/gpt-5.4");
		assertThat(headers).containsEntry("x-hermes-session-key", "test-session");
		assertThat(headers).containsEntry("x-hermes-message-channel", "slack");

		// user goes in JSON body, not headers
		assertThat(options.getUser()).isEqualTo("conv:test-123");
	}
}
