package com.example.attend.access.infrastructure.mybatis;

import com.example.attend.access.application.DepartmentAdminInvitationDispatchJob;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 관리자 초대 SMTP outbox의 claim·재시도 상태만 다루는 SQL 경계다. */
@Mapper
public interface DepartmentAdminInvitationOutboxMapper {
    /** 현재 전달 가능한 작업 ID를 제한된 batch로 조회한다. */
    List<Long> selectReadyIds(@Param("now") Instant now, @Param("limit") int limit);

    /** 작업 하나를 lease와 함께 처리 중 상태로 원자 전이한다. */
    DepartmentAdminInvitationDispatchJob claim(
            @Param("id") long id, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    /** SMTP 전달 성공을 현재 claim 버전에만 기록한다. */
    int markSent(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("sentAt") Instant sentAt);

    /** 일시 실패 작업의 다음 시도 시각과 안전한 오류 코드를 기록한다. */
    int markRetry(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode, @Param("updatedAt") Instant updatedAt);

    /** 재시도 한도를 소진한 작업을 관리자 재전송 대기 상태로 끝낸다. */
    int markDead(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("errorCode") String errorCode, @Param("updatedAt") Instant updatedAt);

    /** 권한·부서 상태가 바뀐 작업을 전송하지 않고 취소한다. */
    int markCanceled(@Param("id") long id, @Param("claimVersion") long claimVersion,
            @Param("updatedAt") Instant updatedAt);

    /** 비정상 종료로 남은 lease를 다시 시도할 수 있게 복구한다. */
    int recoverExpiredLeases(@Param("now") Instant now);

    /** 활성 부서에 속한 최종 실패 작업만 수동 재전송 대기로 초기화한다. */
    int resetDead(@Param("id") long id, @Param("departmentId") long departmentId,
            @Param("now") Instant now);
}
