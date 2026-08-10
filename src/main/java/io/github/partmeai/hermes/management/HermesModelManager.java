package io.github.partmeai.hermes.management;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import org.springframework.util.Assert;

/**
 * Manages Hermes model discovery via the API Server's {@code GET /v1/models} endpoint.
 * <p>
 * Model listings are cached briefly and concurrent reactive refreshes share one
 * upstream request. This prevents application startup or health probes from
 * amplifying identical discovery requests.
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@Slf4j
public class HermesModelManager {

	public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

	private final HermesApi api;
	private final long cacheTtlNanos;
	private final Object cacheMonitor = new Object();
	private final AtomicReference<Mono<List<String>>> refreshInFlight = new AtomicReference<>();

	private volatile List<String> cachedModels = List.of();
	private volatile long cacheExpiresAtNanos;

	public HermesModelManager(HermesApi api) {
		this(api, DEFAULT_CACHE_TTL);
	}

	public HermesModelManager(HermesApi api, Duration cacheTtl) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(cacheTtl, "cacheTtl must not be null");
		Assert.isTrue(!cacheTtl.isNegative(), "cacheTtl must not be negative");
		this.api = api;
		this.cacheTtlNanos = cacheTtl.toNanos();
	}

	/**
	 * Lists the model identifiers, refreshing an expired cache at most once among
	 * concurrent callers of this synchronous method.
	 *
	 * @return immutable model identifier list, or the last cached list on failure
	 */
	public List<String> listModels() {
		List<String> cached = currentCache();
		if (Objects.nonNull(cached)) {
			log.trace("Using cached Hermes model list: size={}", cached.size());
			return cached;
		}
		synchronized (this.cacheMonitor) {
			cached = currentCache();
			if (Objects.nonNull(cached)) {
				return cached;
			}
			try {
				log.debug("Refreshing Hermes model list synchronously");
				List<String> models = toModelIds(this.api.listModels());
				updateCache(models);
				return models;
			}
			catch (Exception ex) {
				log.warn("Failed to list models from Hermes API: {}", ex.getMessage());
				return this.cachedModels;
			}
		}
	}

	/**
	 * Lists the model identifiers without blocking and coalesces concurrent cache
	 * misses into one upstream request.
	 *
	 * @return a publisher yielding the immutable model identifier list
	 */
	public Mono<List<String>> listModelsAsync() {
		List<String> cached = currentCache();
		if (Objects.nonNull(cached)) {
			log.trace("Using cached Hermes model list asynchronously: size={}", cached.size());
			return Mono.just(cached);
		}

		Mono<List<String>> activeRefresh = this.refreshInFlight.get();
		if (Objects.nonNull(activeRefresh)) {
			log.trace("Joining in-flight Hermes model-list refresh");
			return activeRefresh;
		}

		Mono<List<String>> refresh = this.api.listModelsAsync()
			.doOnSubscribe(subscription -> log.debug("Refreshing Hermes model list asynchronously"))
			.map(HermesModelManager::toModelIds)
			.doOnNext(this::updateCache)
			.onErrorResume(ex -> {
				log.warn("Failed to list models asynchronously from Hermes API: {}", ex.getMessage());
				return Mono.just(this.cachedModels);
			})
			.doFinally(signal -> this.refreshInFlight.set(null))
			.cache();
		if (this.refreshInFlight.compareAndSet(null, refresh)) {
			return refresh;
		}
		return this.refreshInFlight.get();
	}

	public Optional<HermesApi.ModelResponse> getModel(String modelId) {
		try {
			return Optional.ofNullable(this.api.getModel(modelId));
		}
		catch (Exception ex) {
			log.warn("Failed to get model '{}': {}", modelId, ex.getMessage());
			return Optional.empty();
		}
	}

	public boolean isModelAvailable(String modelId) {
		return listModels().contains(modelId);
	}

	/** The default model id: {@code hermes-agent}. */
	public String getDefaultModel() {
		return HermesApiConstants.DEFAULT_MODEL;
	}

	public void invalidateCache() {
		this.cacheExpiresAtNanos = 0;
	}

	private List<String> currentCache() {
		return System.nanoTime() < this.cacheExpiresAtNanos ? this.cachedModels : null;
	}

	private void updateCache(List<String> models) {
		this.cachedModels = List.copyOf(models);
		this.cacheExpiresAtNanos = System.nanoTime() + this.cacheTtlNanos;
		log.debug("Updated Hermes model-list cache: size={}, ttlNanos={}",
				models.size(), this.cacheTtlNanos);
	}

	private static List<String> toModelIds(HermesApi.ListModelResponse response) {
		if (Objects.isNull(response) || Objects.isNull(response.data())) {
			return List.of();
		}
		return response.data().stream().map(HermesApi.ModelData::id).toList();
	}
}
