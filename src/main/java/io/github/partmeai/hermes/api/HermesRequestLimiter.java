package io.github.partmeai.hermes.api;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Hermes 请求并发门禁。
 *
 * <p>使用原子计数器为同步请求、单值响应和流式响应提供统一的无等待并发上限。
 * 达到上限时立即以 {@link RejectedExecutionException} 拒绝请求；已获得的许可在同步
 * 调用结束或 Reactor 序列终止、取消时释放。</p>
 */
@Slf4j
final class HermesRequestLimiter {

	/** 同步、异步与流式请求共享的最大并发数。 */
	private final int maxConcurrentRequests;

	/** 当前已获得许可且尚未完成或取消的请求数。 */
	private final AtomicInteger inFlightRequests = new AtomicInteger();

	HermesRequestLimiter(int maxConcurrentRequests) {
		if (maxConcurrentRequests < 1) {
			throw new IllegalArgumentException("maxConcurrentRequests must be greater than zero");
		}
		this.maxConcurrentRequests = maxConcurrentRequests;
		log.debug("Initialized Hermes request limiter: maxConcurrentRequests={}", maxConcurrentRequests);
	}

	/**
	 * 在获得并发许可后同步执行请求，并在任何退出路径释放许可。
	 *
	 * @param request 实际发起请求的延迟执行器
	 * @param <T> 请求结果类型
	 * @return 请求结果
	 * @throws RejectedExecutionException 当前在途请求已达到上限时抛出
	 */
	<T> T execute(Supplier<T> request) {
		acquireOrThrow();
		try {
			return request.get();
		}
		finally {
			release();
		}
	}

	/**
	 * 为单值响应添加订阅期并发门禁。
	 *
	 * @param request 受保护的单值发布者
	 * @param <T> 响应元素类型
	 * @return 延迟到订阅时申请许可，并在完成、失败或取消时释放许可的发布者
	 */
	<T> Mono<T> guard(Mono<T> request) {
		return Mono.defer(() -> {
			if (!tryAcquire()) {
				return Mono.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	/**
	 * 为多值或流式响应添加订阅期并发门禁。
	 *
	 * @param request 受保护的多值发布者
	 * @param <T> 响应元素类型
	 * @return 延迟到订阅时申请许可，并在完成、失败或取消时释放许可的发布者
	 */
	<T> Flux<T> guard(Flux<T> request) {
		return Flux.defer(() -> {
			if (!tryAcquire()) {
				return Flux.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	/**
	 * 返回当前已经占用许可的请求数。
	 *
	 * @return 当前在途请求数
	 */
	int getInFlightRequestCount() {
		return this.inFlightRequests.get();
	}

	/**
	 * 返回允许的最大并发请求数。
	 *
	 * @return 并发上限
	 */
	int getMaxConcurrentRequests() {
		return this.maxConcurrentRequests;
	}

	private void acquireOrThrow() {
		if (!tryAcquire()) {
			throw rejected();
		}
	}

	private boolean tryAcquire() {
		while (true) {
			int current = this.inFlightRequests.get();
			if (current >= this.maxConcurrentRequests) {
				log.debug("Rejecting Hermes request: inFlight={}, maxConcurrentRequests={}",
						current, this.maxConcurrentRequests);
				return false;
			}
			if (this.inFlightRequests.compareAndSet(current, current + 1)) {
				if (log.isTraceEnabled()) {
					log.trace("Acquired Hermes request slot: inFlight={}/{}",
							current + 1, this.maxConcurrentRequests);
				}
				return true;
			}
		}
	}

	private void release() {
		int remaining = this.inFlightRequests.decrementAndGet();
		if (log.isTraceEnabled()) {
			log.trace("Released Hermes request slot: inFlight={}/{}",
					remaining, this.maxConcurrentRequests);
		}
	}

	private RejectedExecutionException rejected() {
		return new RejectedExecutionException(
				"Hermes concurrent request limit exceeded: " + this.maxConcurrentRequests);
	}
}
