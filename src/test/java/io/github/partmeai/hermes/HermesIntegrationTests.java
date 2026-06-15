package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;

import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesApi.Message;
import io.github.partmeai.hermes.api.HermesApi.Message.Role;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HermesIntegrationTests {

	private static final Logger log = LoggerFactory.getLogger(HermesIntegrationTests.class);

	private static final String BASE_URL = "http://localhost:8642";
	private static final String API_KEY = "change-me-local-dev";

	private HermesApi api;
	private HermesChatModel chatModel;

	@BeforeAll
	void setUp() {
		var requestFactory = new SimpleClientHttpRequestFactory();
		var restClientBuilder = RestClient.builder()
			.requestFactory(requestFactory)
			.defaultHeader("Authorization", "Bearer " + API_KEY);
		var webClientBuilder = WebClient.builder()
			.clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(
				java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()))
			.defaultHeader("Authorization", "Bearer " + API_KEY);

		api = HermesApi.builder().baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder).webClientBuilder(webClientBuilder).build();
		chatModel = HermesChatModel.builder().api(api)
			.defaultOptions(HermesChatOptions.builder().model(HermesModel.HERMES_AGENT.id()).build()).build();
	}

	// ==============================
	// Health
	// ==============================

	@Test
	void healthShouldReturnOk() {
		var health = api.health();
		assertThat(health).containsEntry("status", "ok");
		assertThat(health).containsEntry("platform", "hermes-agent");
	}

	@Test
	void healthDetailedShouldReturnStatus() {
		var health = api.healthDetailed();
		assertThat(health).containsKey("status");
	}

	// ==============================
	// Models
	// ==============================

	@Test
	void listModelsShouldReturnHermesAgent() {
		var response = api.listModels();
		assertThat(response).isNotNull();
		assertThat(response.object()).isEqualTo("list");
		assertThat(response.data()).isNotEmpty();
		var ids = response.data().stream().map(HermesApi.ModelData::id).toList();
		assertThat(ids).contains("hermes-agent");
	}

	@Test
	void healthV1ShouldReturnOk() {
		var response = api.healthV1();
		assertThat(response).isNotNull();
		assertThat(response.get("status")).isEqualTo("ok");
	}

	// ==============================
	// Capabilities
	// ==============================

	@Test
	void capabilitiesShouldListFeatures() {
		var caps = api.getCapabilities();
		assertThat(caps).isNotNull();
		assertThat(caps.platform()).isEqualTo("hermes-agent");
		assertThat(caps.features()).containsKeys("chat_completions", "responses_api");
	}

	// ==============================
	// Chat Completions
	// ==============================

	@Test
	void chatShouldReturnResponse() {
		var request = HermesApi.ChatRequest.builder("hermes-agent")
			.messages(List.of(Message.builder(Role.USER).content("Reply in one word: hello").build()))
			.stream(false).build();

		HermesApi.ChatResponse response = api.chat(request);

		assertThat(response).isNotNull();
		assertThat(response.id()).isNotEmpty();
		assertThat(response.model()).isEqualTo("hermes-agent");
		assertThat(response.choices()).hasSize(1);
		assertThat(response.choices().get(0).message().role()).isEqualTo(Role.ASSISTANT);
		log.info("Chat response: {}", response.choices().get(0).message().content());
	}

	@Test
	void streamingChatShouldReturnChunks() {
		var request = HermesApi.ChatRequest.builder("hermes-agent")
			.messages(List.of(Message.builder(Role.USER).content("Count: 1").build()))
			.stream(true).build();

		Flux<HermesApi.ChatResponse> flux = api.streamingChat(request);
		List<HermesApi.ChatResponse> chunks = flux.collectList().block();

		assertThat(chunks).isNotNull().isNotEmpty();
		var first = chunks.get(0);
		assertThat(first.choices().get(0).delta()).isNotNull();
		log.info("Streaming chunks received: {}", chunks.size());
	}

	// ==============================
	// Spring AI ChatModel
	// ==============================

	@Test
	void springAiChatModelCallShouldWork() {
		var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt("Say: OK"));

		assertThat(response).isNotNull();
		assertThat(response.getResults()).isNotEmpty();
		var text = response.getResult().getOutput().getText();
		assertThat(text).isNotBlank();
		log.info("Spring AI ChatModel: {}", text);
	}

	// ==============================
	// Hermes headers
	// ==============================

	@Test
	void hermessSessionHeadersShouldBeMappedCorrectly() {
		var options = HermesChatOptions.builder()
			.model(HermesModel.HERMES_AGENT.id())
			.hermesSessionKey("agent:main:test:user-1")
			.hermesSessionId("transcript-001")
			.user("user-1")
			.build();

		var headers = options.toHttpHeaders();
		assertThat(headers).containsEntry("X-Hermes-Session-Key", "agent:main:test:user-1");
		assertThat(headers).containsEntry("X-Hermes-Session-Id", "transcript-001");
		assertThat(options.getUser()).isEqualTo("user-1");
	}

	// ==============================
	// Runs API
	// ==============================

	@Test
	void createRunShouldReturnRunId() {
		var req = new HermesApi.RunRequest("Say hello", "hermes-agent", null, null, null, null, null);
		HermesApi.Run run = api.createRun(req);

		assertThat(run).isNotNull();
		assertThat(run.runId()).isNotEmpty();
		assertThat(run.status()).isIn("started", "completed", "running");
		log.info("Run created: {} status={}", run.runId(), run.status());
	}
}
