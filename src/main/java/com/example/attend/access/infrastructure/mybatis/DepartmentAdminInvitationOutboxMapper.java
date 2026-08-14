package com.example.attend.access.infrastructure.mybatis;

import com.example.attend.access.application.DepartmentAdminInvitationDispatchJob;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 관리자 초대 SMTP outbox의 claim·재시도 상태만 다루는 SQL 경계다. */
@Mapper
public interface DepartmentAdminInvitationOutboxMapper {
    List<Long> selectReadyIds(@Param("now") Instant now, @Param("limit") int limit);

    DepartmentAdminInvitationDispatchJob claim(
            @Param("id") long id, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    int markSent(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("sentAt") Instant sentAt);

    int markRetry(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode, @Param("updatedAt") Instant updatedAt);

    int markDead(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("errorCode") String errorCode, @Param("updatedAt") Instant updatedAt);

    int markCanceled(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("updatedAt") Instant updatedAt);

    int recoverExpiredLeases(@Param("now") Instant now);

    int resetDead(@Param("id") long id, @Param("departmentId") long departmentId,
            @Param("now") Instant now);
}
