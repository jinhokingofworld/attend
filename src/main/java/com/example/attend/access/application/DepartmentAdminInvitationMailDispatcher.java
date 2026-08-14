package com.example.attend.access.application;

import com.example.attend.config.AttendanceMailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DB outbox의 부서 관리자 초대를 SMTP로 at-least-once 전달한다. */
@Component
@ConditionalOnProperty(name = "attendance.mail.enabled", havingValue = "true")
public final class DepartmentAdminInvitationMailDispatcher {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final DepartmentAdminInvitationOutboxService outboxService;
    private final CredentialTokenService tokenService;
    private final JavaMailSender mailSender;
    private final AttendanceMailProperties properties;
    private final Clock clock;

    public DepartmentAdminInvitationMailDispatcher(
            DepartmentAdminInvitationOutboxService outboxService,
            CredentialTokenService tokenService,
            JavaMailSender mailSender,
            AttendanceMailProperties properties,
            Clock clock) {
        this.outboxService = outboxService;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
    }

    /** 재기동 후에도 ready·lease-expired 작업을 회복해 전송한다. */
    @Scheduled(fixedDelayString = "${attendance.mail.poll-delay:PT10S}")
    public void dispatchReady() {
        Instant now = clock.instant();
        outboxService.recoverExpiredLeases(now);
        List<Long> ids = outboxService.readyIds(now);
        for (long id : ids) {
            dispatch(id);
        }
    }

    private void dispatch(long id) {
        Instant now = clock.instant();
        DepartmentAdminInvitationDispatchJob job = outboxService.claim(
                id, now, now.plus(LEASE_DURATION));
        if (job == null) {
            return;
        }
        if (!job.eligible()) {
            outboxService.cancel(job, clock.instant());
            return;
        }
        try {
            send(job);
            outboxService.sent(job, clock.instant());
        } catch (RuntimeException exception) {
            fail(job);
        }
    }

    private void send(DepartmentAdminInvitationDispatchJob job) {
        if (properties.from() == null) {
            throw new IllegalStateException("MAIL_FROM_NOT_CONFIGURED");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(job.recipientEmail());
        if ("INVITATION".equals(job.deliveryType())) {
            IssuedCredentialLink issued = tokenService.issueInvitationForDelivery(
                    job.issuedByAccountId(), job.accountId());
            message.setSubject("[출석 관리] " + job.departmentName() + " 관리자 초대");
            message.setText("'" + job.departmentName() + "' 부서 관리자 권한이 부여되었습니다.\n"
                    + "아래 링크에서 비밀번호를 설정하세요. 링크는 " + issued.expiresAt()
                    + "까지 한 번만 사용할 수 있습니다.\n\n" + issued.link());
        } else {
            message.setSubject("[출석 관리] " + job.departmentName() + " 관리자 권한 안내");
            message.setText("'" + job.departmentName()
                    + "' 부서 관리자 권한이 부여되었습니다. 기존 계정으로 로그인해 주세요.");
        }
        mailSender.send(message);
    }

    private void fail(DepartmentAdminInvitationDispatchJob job) {
        Instant now = clock.instant();
        if (job.attemptCount() >= properties.maxAttempts()) {
            outboxService.dead(job, "SMTP_DELIVERY_FAILED", now);
            return;
        }
        long seconds = Math.min(300, 30L * (1L << Math.min(3, job.attemptCount() - 1)));
        outboxService.retry(job, now.plusSeconds(seconds), "SMTP_DELIVERY_FAILED", now);
    }
}
