package com.example.attend.operations.infrastructure.mybatis;

import com.example.attend.operations.domain.FinalizationOperationalAlertJob;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 마감 재시도 소진 이벤트의 생성과 Telegram 전달 lease를 관리한다. */
@Mapper
public interface FinalizationOperationalEventMapper {

    Long insertRetryExhaustedEvent(
            @Param("attendanceDayId") long attendanceDayId,
            @Param("incidentClaimVersion") long incidentClaimVersion,
            @Param("errorCode") String errorCode,
            @Param("occurredAt") Instant occurredAt);

    List<Long> selectReadyEventIds(
            @Param("now") Instant now,
            @Param("limit") int limit);

    FinalizationOperationalAlertJob claimEvent(
            @Param("id") long id,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    int markSent(
            @Param("id") long id,
            @Param("deliveryClaimVersion") long deliveryClaimVersion,
            @Param("telegramMessageId") long telegramMessageId,
            @Param("sentAt") Instant sentAt);

    int markRetry(
            @Param("id") long id,
            @Param("deliveryClaimVersion") long deliveryClaimVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("updatedAt") Instant updatedAt);

    int recoverExpiredLeases(@Param("now") Instant now);
}
