package com.example.attend.attendance.application;

import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.config.AttendanceFinalizationSchedulerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 마감 작업의 다음 실행 시각, lease 선점과 실패 backoff를 DB에 기록한다. */
@Service
public class AttendanceFinalizationQueueService {

	private static final List<Duration> RETRY_DELAYS = List.of(
			Duration.ofMinutes(1),
			Duration.ofMinutes(2),
			Duration.ofMinutes(4),
			Duration.ofMinutes(8),
			Duration.ofMinutes(16));

	private final AttendanceDayMapper dayMapper;
	private final AttendanceFinalizationSchedulerProperties properties;
	private final Clock clock;

	public AttendanceFinalizationQueueService(
			AttendanceDayMapper dayMapper,
			AttendanceFinalizationSchedulerProperties properties,
			Clock clock
	) {
		this.dayMapper = dayMapper;
		this.properties = properties;
		this.clock = clock;
	}

	/** 미마감 작업 중 가장 이른 due, retry 또는 lease 만료 시각을 반환한다. */
	@Transactional(readOnly = true)
	public Instant findNextActionAt() {
		return dayMapper.selectNextFinalizationActionAt(clock.instant());
	}

	/** 현재 실행 가능한 날짜를 claim version으로 선점한다. */
	@Transactional
	public List<AttendanceFinalizationClaim> claimReadyDays() {
		Instant now = clock.instant();
		Instant leaseUntil = now.plus(properties.leaseDuration());
		List<Long> candidateIds = dayMapper.selectReadyFinalizationDayIds(
				now, properties.claimLimit());
		List<AttendanceFinalizationClaim> claims = new ArrayList<>(candidateIds.size());
		for (long candidateId : candidateIds) {
			AttendanceFinalizationClaim claim = dayMapper.claimFinalizationDay(
					candidateId, now, leaseUntil);
			if (claim != null) {
				claims.add(claim);
			}
		}
		return claims;
	}

	/** 실패한 claim을 1·2·4·8·16분 backoff 또는 최종 소진 상태로 전환한다. */
	@Transactional
	public boolean recordFailure(
			AttendanceFinalizationClaim claim,
			RuntimeException failure
	) {
		Instant failedAt = clock.instant();
		int newFailureCount = claim.failureCount() + 1;
		Instant nextAttemptAt = newFailureCount <= RETRY_DELAYS.size()
				? failedAt.plus(RETRY_DELAYS.get(newFailureCount - 1))
				: null;
		String errorCode = failure.getClass().getSimpleName();
		if (errorCode.length() > 80) {
			errorCode = errorCode.substring(0, 80);
		}
		return dayMapper.markFinalizationFailure(
				claim.attendanceDayId(),
				claim.claimVersion(),
				newFailureCount,
				nextAttemptAt,
				errorCode,
				failedAt) == 1;
	}

	public int claimLimit() {
		return properties.claimLimit();
	}

	public Duration recoveryDelay() {
		return properties.recoveryDelay();
	}
}
