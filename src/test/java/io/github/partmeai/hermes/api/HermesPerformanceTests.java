package io.github.partmeai.hermes.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Flux;

import io.github.partmeai.hermes.api.HermesApi.ChatRequest;
import io.github.partmeai.hermes.api.HermesApi.Message;
import io.github.partmeai.hermes.api.HermesApi.Message.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HermesPerformanceTests {

	private static final int CONCURRENCY = 300;
	private static final String CHAT_RESPONSE = """
			{"id":"chat-1","object":"chat.completion","created":1,"model":"hermes-agent",
			 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
			""";

	private MockWebServer server;
	private HermesApi api;
	private final AtomicReference<Throwable> unexpectedDroppedError = new AtomicReference<>();

	@BeforeEach
	void setUp() throws Exception {
		this.server = new MockWebServer();
		this.server.start();
		this.api = HermesApi.builder().baseUrl(this.server.url("/").toString()).build();
		Hooks.onErrorDropped(error -> {
			Throwable cause = Exceptions.unwrap(error);
			if (!isCancellation(cause)) {
				this.unexpectedDroppedError.compareAndSet(null, cause);
			}
		});
	}

	@AfterEach
	void tearDown() throws Exception {
		Hooks.resetOnErrorDropped();
		this.server.shutdown();
		assertThat(this.unexpectedDroppedError.get()).isNull();
	}

	@Test
	void shouldCompleteThreeHundredConcurrentReactiveRequests() {
		for (int i = 0; i < CONCURRENCY; i++) {
			this.server.enqueue(jsonResponse(CHAT_RESPONSE));
		}

		List<HermesApi.ChatResponse> responses = Flux.range(0, CONCURRENCY)
				.flatMap(index -> this.api.chatAsync(chatRequest(false)), CONCURRENCY)
				.collectList()
				.block(Duration.ofSeconds(30));

		assertThat(responses).hasSize(CONCURRENCY);
		assertThat(this.server.getRequestCount()).isEqualTo(CONCURRENCY);
	}

	@Test
	void shouldCancelThreeHundredSseSubscriptionsWithoutRetainingState() {
		for (int i = 0; i < CONCURRENCY; i++) {
			this.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		}

		List<Disposable> subscriptions = new ArrayList<>(CONCURRENCY);
		for (int i = 0; i < CONCURRENCY; i++) {
			subscriptions.add(this.api.streamingChat(chatRequest(true)).subscribe());
		}

		Integer observedRequestCount = Flux.interval(Duration.ofMillis(10))
				.map(tick -> this.server.getRequestCount())
				.filter(count -> count == CONCURRENCY)
				.next()
				.block(Duration.ofSeconds(30));
		assertThat(observedRequestCount).isEqualTo(CONCURRENCY);
		assertThat(this.api.getActiveStreamCount()).isEqualTo(CONCURRENCY);

		subscriptions.forEach(Disposable::dispose);
		assertThat(this.api.getActiveStreamCount()).isZero();
	}

	@Test
	void shouldNotRetryFailedPostByDefault() {
		this.server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));

		assertThatThrownBy(() -> this.api.chatAsync(chatRequest(false)).block(Duration.ofSeconds(5)))
				.isInstanceOf(RuntimeException.class);
		assertThat(this.server.getRequestCount()).isEqualTo(1);
	}

	private ChatRequest chatRequest(boolean stream) {
		Message message = Message.builder(Role.USER).content("hello").build();
		return ChatRequest.builder("hermes-agent").messages(List.of(message)).stream(stream).build();
	}

	private MockResponse jsonResponse(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}

	private boolean isCancellation(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof CancellationException) {
				return true;
			}
		}
		return false;
	}
}
