package com.example.attend.access.application;

import com.example.attend.access.infrastructure.mybatis.DepartmentAdminInvitationOutboxMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SMTP worker가 짧은 DB transaction으로 outbox 상태를 전이하게 한다. */
@Service
public class DepartmentAdminInvitationOutboxService {
    private final DepartmentAdminInvitationOutboxMapper mapper;

    /** SMTP outbox 저장 경계를 주입한다. */
    public DepartmentAdminInvitationOutboxService(DepartmentAdminInvitationOutboxMapper mapper) {
        this.mapper = mapper;
    }

    /** 전달 시각이 지난 작업 ID를 읽는다. */
    @Transactional(readOnly = true)
    public List<Long> readyIds(Instant now) {
        return mapper.selectReadyIds(now, 20);
    }

    /** 한 작업을 단일 worker가 처리하도록 claim한다. */
    @Transactional
    public DepartmentAdminInvitationDispatchJob claim(long id, Instant now, Instant leaseUntil) {
        return mapper.claim(id, now, leaseUntil);
    }

    /** 현재 claim의 SMTP 전달 성공을 저장한다. */
    @Transactional
    public void sent(DepartmentAdminInvitationDispatchJob job, Instant now) {
        mapper.markSent(job.id(), job.claimVersion(), now);
    }

    /** 현재 claim을 재시도 상태로 바꾼다. */
    @Transactional
    public void retry(DepartmentAdminInvitationDispatchJob job, Instant nextAttemptAt, String code, Instant now) {
        mapper.markRetry(job.id(), job.claimVersion(), nextAttemptAt, code, now);
    }

    /** 현재 claim을 사람이 재전송할 수 있는 최종 실패로 바꾼다. */
    @Transactional
    public void dead(DepartmentAdminInvitationDispatchJob job, String code, Instant now) {
        mapper.markDead(job.id(), job.claimVersion(), code, now);
    }

    /** 더는 자격이 없는 현재 claim을 취소한다. */
    @Transactional
    public void cancel(DepartmentAdminInvitationDispatchJob job, Instant now) {
        mapper.markCanceled(job.id(), job.claimVersion(), now);
    }

    /** worker 재시작 뒤 만료된 lease를 복구한다. */
    @Transactional
    public void recoverExpiredLeases(Instant now) {
        mapper.recoverExpiredLeases(now);
    }
}
