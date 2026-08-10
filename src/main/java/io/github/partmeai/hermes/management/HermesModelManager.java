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
 * Hermes 模型发现与缓存管理器。
 *
 * <p>通过 API Server 的 {@code GET /v1/models} 端点发现模型标识，并在短 TTL 内
 * 缓存不可变结果。同步缓存失效由监视器串行刷新；响应式缓存失效通过共享且缓存的
 * {@link Mono} 合并并发刷新，避免应用启动或健康检查放大相同的上游请求。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@Slf4j
public class HermesModelManager {

	/** 默认模型列表缓存时长。 */
	public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

	/** 负责实际模型发现请求的 Hermes API 客户端。 */
	private final HermesApi api;

	/** 预先换算为纳秒的缓存时长，用于与 {@link System#nanoTime()} 配合。 */
	private final long cacheTtlNanos;

	/** 串行化同步缓存刷新的监视器。 */
	private final Object cacheMonitor = new Object();

	/** 当前共享的异步刷新序列；无刷新时为 {@code null}。 */
	private final AtomicReference<Mono<List<String>>> refreshInFlight = new AtomicReference<>();

	/** 最近一次成功刷新或失败回退所使用的不可变模型标识列表。 */
	private volatile List<String> cachedModels = List.of();

	/** 以 {@link System#nanoTime()} 时间基准表示的缓存过期时刻。 */
	private volatile long cacheExpiresAtNanos;

	/**
	 * 使用默认的 30 秒缓存时长创建模型管理器。
	 *
	 * @param api Hermes API 客户端
	 * @throws IllegalArgumentException {@code api} 为 {@code null} 时抛出
	 */
	public HermesModelManager(HermesApi api) {
		this(api, DEFAULT_CACHE_TTL);
	}

	/**
	 * 使用指定缓存时长创建模型管理器。
	 *
	 * @param api Hermes API 客户端
	 * @param cacheTtl 模型列表缓存时长，允许为零但不能为负数
	 * @throws IllegalArgumentException {@code api} 或 {@code cacheTtl} 为 {@code null}，或缓存时长为负数时抛出
	 */
	public HermesModelManager(HermesApi api, Duration cacheTtl) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(cacheTtl, "cacheTtl must not be null");
		Assert.isTrue(!cacheTtl.isNegative(), "cacheTtl must not be negative");
		this.api = api;
		this.cacheTtlNanos = cacheTtl.toNanos();
	}

	/**
	 * 同步列出模型标识。
	 *
	 * <p>缓存有效时直接返回；缓存失效时在监视器内再次检查，并确保并发同步调用最多
	 * 发起一次刷新。刷新失败时保留并返回上一次缓存，不向调用方传播上游异常。</p>
	 *
	 * @return 不可变模型标识列表；刷新失败时返回上一次缓存
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
	 * 异步列出模型标识。
	 *
	 * <p>缓存失效时使用原子引用发布一个经 {@link Mono#cache()} 共享的刷新序列；并发
	 * 调用者加入同一次上游请求。刷新失败时发布上一次缓存，并在序列终止后清除在途引用。</p>
	 *
	 * @return 发布不可变模型标识列表的单值序列
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

	/**
	 * 按模型标识查询单个模型。
	 *
	 * @param modelId 模型标识
	 * @return 查询成功时包含模型响应；API 返回 {@code null} 或调用失败时为空
	 */
	public Optional<HermesApi.ModelResponse> getModel(String modelId) {
		try {
			return Optional.ofNullable(this.api.getModel(modelId));
		}
		catch (Exception ex) {
			log.warn("Failed to get model '{}': {}", modelId, ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * 判断指定模型是否出现在当前模型列表中。
	 *
	 * @param modelId 待检查的模型标识
	 * @return 模型列表包含该标识时返回 {@code true}
	 */
	public boolean isModelAvailable(String modelId) {
		return listModels().contains(modelId);
	}

	/**
	 * 返回默认模型标识。
	 *
	 * @return 固定值 {@code hermes-agent}
	 */
	public String getDefaultModel() {
		return HermesApiConstants.DEFAULT_MODEL;
	}

	/**
	 * 立即使模型列表缓存过期。
	 *
	 * <p>该操作不清空上一次模型列表，也不会取消已经开始的异步刷新；下一次读取会触发刷新。</p>
	 */
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
