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

    public DepartmentAdminInvitationOutboxService(DepartmentAdminInvitationOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Long> readyIds(Instant now) {
        return mapper.selectReadyIds(now, 20);
    }

    @Transactional
    public DepartmentAdminInvitationDispatchJob claim(long id, Instant now, Instant leaseUntil) {
        return mapper.claim(id, now, leaseUntil);
    }

    @Transactional
    public void sent(DepartmentAdminInvitationDispatchJob job, Instant now) {
        mapper.markSent(job.id(), job.claimVersion(), now);
    }

    @Transactional
    public void retry(DepartmentAdminInvitationDispatchJob job, Instant nextAttemptAt, String code, Instant now) {
        mapper.markRetry(job.id(), job.claimVersion(), nextAttemptAt, code, now);
    }

    @Transactional
    public void dead(DepartmentAdminInvitationDispatchJob job, String code, Instant now) {
        mapper.markDead(job.id(), job.claimVersion(), code, now);
    }

    @Transactional
    public void cancel(DepartmentAdminInvitationDispatchJob job, Instant now) {
        mapper.markCanceled(job.id(), job.claimVersion(), now);
    }

    @Transactional
    public void recoverExpiredLeases(Instant now) {
        mapper.recoverExpiredLeases(now);
    }
}
