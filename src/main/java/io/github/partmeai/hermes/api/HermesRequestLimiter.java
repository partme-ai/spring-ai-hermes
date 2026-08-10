package io.github.partmeai.hermes.api;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
final class HermesRequestLimiter {

	private final int maxConcurrentRequests;
	private final AtomicInteger inFlightRequests = new AtomicInteger();

	HermesRequestLimiter(int maxConcurrentRequests) {
		if (maxConcurrentRequests < 1) {
			throw new IllegalArgumentException("maxConcurrentRequests must be greater than zero");
		}
		this.maxConcurrentRequests = maxConcurrentRequests;
		log.debug("Initialized Hermes request limiter: maxConcurrentRequests={}", maxConcurrentRequests);
	}

	<T> T execute(Supplier<T> request) {
		acquireOrThrow();
		try {
			return request.get();
		}
		finally {
			release();
		}
	}

	<T> Mono<T> guard(Mono<T> request) {
		return Mono.defer(() -> {
			if (!tryAcquire()) {
				return Mono.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	<T> Flux<T> guard(Flux<T> request) {
		return Flux.defer(() -> {
			if (!tryAcquire()) {
				return Flux.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	int getInFlightRequestCount() {
		return this.inFlightRequests.get();
	}

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
