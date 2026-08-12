package com.example.attend.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.operations.domain.FinalizationOperationalAlertJob;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinalizationOperationalAlertFormatterTest {

    @Test
    void formatsOnlyAllowlistedIncidentMetadataAndTrackingLink() {
        FinalizationOperationalAlertFormatter formatter =
                new FinalizationOperationalAlertFormatter(
                        new AdminSecurityProperties(
                                false,
                                "test-account-pepper-at-least-32-bytes",
                                "https://attendance.example.test"));
        FinalizationOperationalAlertJob job = new FinalizationOperationalAlertJob(
                77L,
                "FINALIZATION_RETRY_EXHAUSTED",
                31L,
                9L,
                4L,
                "유치부\n위조 줄",
                LocalDate.of(2026, 8, 12),
                Instant.parse("2026-08-12T00:00:00Z"),
                Instant.parse("2026-08-12T00:31:00Z"),
                6,
                "IllegalStateException",
                1,
                1L);

        String message = formatter.format(job);

        assertThat(message)
                .contains("FINALIZATION_RETRY_EXHAUSTED")
                .contains("유치부 위조 줄 (ID 4)")
                .contains("2026-08-12 (출석일 ID 31)")
                .contains("총 시도 횟수: 6")
                .contains("오류 코드: IllegalStateException")
                .contains("추적 키: finalization-event:77")
                .contains("https://attendance.example.test/admin/system/operations")
                .doesNotContain("password", "member", "chat");
    }
}
