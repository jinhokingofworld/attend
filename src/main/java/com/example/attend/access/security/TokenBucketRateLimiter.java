package com.example.attend.access.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 단일 인스턴스 MVP에서 사용하는 thread-safe 메모리 token bucket이다.
 */
public final class TokenBucketRateLimiter {

	private static final int MAX_BUCKET_COUNT = 10_000;
	private final double capacity;
	private final double refillPerMillisecond;
	private final Clock clock;
	private final ConcurrentHashMap<String, Bucket> buckets =
			new ConcurrentHashMap<>();

	/**
	 * bucket 용량과 한 token 회복 시간을 설정한다.
	 *
	 * @param capacity 최대 burst 수
	 * @param refillMillis token 하나가 회복되는 밀리초
	 * @param clock 시간 공급자
	 */
	public TokenBucketRateLimiter(
			int capacity,
			long refillMillis,
			Clock clock) {
		this.capacity = capacity;
		this.refillPerMillisecond = 1.0 / refillMillis;
		this.clock = clock;
	}

	/**
	 * key의 token 하나를 원자적으로 소비한다.
	 *
	 * @param key 원문 자격증명을 포함하지 않는 제한 key
	 * @return 요청을 허용하면 {@code true}
	 */
	public boolean tryConsume(String key) {
		if (!buckets.containsKey(key)
				&& buckets.size() >= MAX_BUCKET_COUNT) {
			return false;
		}
		long now = clock.millis();
		AtomicBoolean allowed = new AtomicBoolean();
		buckets.compute(key, (ignored, existing) -> {
			Bucket current = existing == null
					? new Bucket(capacity, now)
					: existing.refill(now, capacity, refillPerMillisecond);
			if (current.tokens >= 1.0) {
				allowed.set(true);
				return new Bucket(current.tokens - 1.0, now);
			}
			return current;
		});
		return allowed.get();
	}

	private record Bucket(double tokens, long updatedAt) {

		private Bucket refill(
				long now,
				double capacity,
				double refillPerMillisecond) {
			double refilled = Math.min(
					capacity,
					tokens + Math.max(0, now - updatedAt)
							* refillPerMillisecond);
			if (refilled >= 1.0) {
				return new Bucket(refilled, now);
			}
			return new Bucket(refilled, now);
		}
	}
}
