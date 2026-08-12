package com.example.attend.operations.application;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.operations.domain.FinalizationOperationalAlertJob;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** 개인정보 없이 개발자가 사고를 식별할 수 있는 Telegram 문자를 만든다. */
@Component
public final class FinalizationOperationalAlertFormatter {
    private static final ZoneId OPERATIONS_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX");
    private final AdminSecurityProperties adminProperties;

    public FinalizationOperationalAlertFormatter(AdminSecurityProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    public String format(FinalizationOperationalAlertJob job) {
        String baseUrl = adminProperties.publicBaseUrl();
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String operationsUrl = (baseUrl == null ? "" : baseUrl)
                + "/admin/system/operations";
        return """
                🚨 FINALIZATION_RETRY_EXHAUSTED
                부서: %s (ID %d)
                출석일: %s (출석일 ID %d)
                최초 실패: %s
                최종 실패: %s
                총 시도 횟수: %d
                오류 코드: %s
                추적 키: finalization-event:%d
                관리자 화면: %s
                """.formatted(
                safeSingleLine(job.departmentName(), 100),
                job.departmentId(),
                job.attendanceDate(),
                job.attendanceDayId(),
                TIME_FORMAT.format(job.firstFailedAt().atZone(OPERATIONS_ZONE)),
                TIME_FORMAT.format(job.occurredAt().atZone(OPERATIONS_ZONE)),
                job.totalAttemptCount(),
                safeSingleLine(job.errorCode(), 80),
                job.id(),
                operationsUrl).strip();
    }

    private static String safeSingleLine(String value, int maxLength) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength);
    }
}
