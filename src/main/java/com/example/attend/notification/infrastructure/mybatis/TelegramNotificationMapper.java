package com.example.attend.notification.infrastructure.mybatis;

import com.example.attend.notification.domain.FinalizationNotificationData;
import com.example.attend.notification.domain.FinalizationNotificationMember;
import com.example.attend.notification.domain.TelegramConnectionRow;
import com.example.attend.notification.domain.TelegramDispatchJob;
import com.example.attend.notification.domain.TelegramLinkTokenRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Telegram 연결, 마감 snapshot, outbox를 저장하는 최소 Mapper다. */
@Mapper
public interface TelegramNotificationMapper {
    FinalizationNotificationData selectFinalizationData(@Param("attendanceDayId") long attendanceDayId);

    List<FinalizationNotificationMember> selectFinalizationMembers(
            @Param("attendanceDayId") long attendanceDayId);

    List<Long> selectConnectedActiveDepartmentAdminAccountIds(
            @Param("departmentId") long departmentId);

    int insertFinalizationOutbox(
            @Param("attendanceDayId") long attendanceDayId,
            @Param("departmentId") long departmentId,
            @Param("accountId") long accountId,
            @Param("messageText") String messageText);

    TelegramConnectionRow selectConnection(@Param("accountId") long accountId);

    Instant selectActiveLinkTokenExpiry(@Param("accountId") long accountId);

    int revokeActiveLinkTokens(@Param("accountId") long accountId, @Param("revokedAt") Instant revokedAt);

    int insertLinkToken(
            @Param("accountId") long accountId,
            @Param("tokenHash") String tokenHash,
            @Param("issuedAt") Instant issuedAt,
            @Param("expiresAt") Instant expiresAt);

    int insertWebhookUpdate(@Param("updateId") long updateId, @Param("receivedAt") Instant receivedAt);

    TelegramLinkTokenRow lockLinkToken(@Param("tokenHash") String tokenHash);

    int consumeLinkToken(@Param("tokenId") long tokenId, @Param("consumedAt") Instant consumedAt);

    int upsertConnection(
            @Param("accountId") long accountId,
            @Param("chatId") long chatId,
            @Param("telegramUserId") long telegramUserId,
            @Param("updatedAt") Instant updatedAt);

    int deleteConnection(@Param("accountId") long accountId);

    int deleteConnectionIfUnchanged(
            @Param("accountId") long accountId,
            @Param("chatId") long chatId,
            @Param("connectionUpdatedAt") Instant connectionUpdatedAt);

    int cancelPendingAccountOutbox(@Param("accountId") long accountId, @Param("updatedAt") Instant updatedAt);

    int insertTestOutbox(@Param("accountId") long accountId, @Param("messageText") String messageText);

    String selectLatestTestStatus(@Param("accountId") long accountId);

    List<Long> selectReadyDispatchJobIds(
            @Param("now") Instant now,
            @Param("limit") int limit);

    Instant selectNextDispatchActionAt();

    TelegramDispatchJob claimDispatchJob(
            @Param("id") long id,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    int markSent(
            @Param("id") long id,
            @Param("claimVersion") long claimVersion,
            @Param("telegramMessageId") long telegramMessageId,
            @Param("sentAt") Instant sentAt);

    int markRetry(
            @Param("id") long id,
            @Param("claimVersion") long claimVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("updatedAt") Instant updatedAt);

    int markDead(
            @Param("id") long id,
            @Param("claimVersion") long claimVersion,
            @Param("errorCode") String errorCode,
            @Param("updatedAt") Instant updatedAt);

    int cancelIneligibleOutbox(@Param("updatedAt") Instant updatedAt);

    int recoverExpiredDispatchLeases(@Param("now") Instant now);
}
