package com.example.attend.notification.application;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.FinalizationNotificationData;
import com.example.attend.notification.domain.FinalizationNotificationMember;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 마감 트랜잭션 안에서 Telegram 발송 작업의 snapshot만 생성한다. */
@Component
public final class AttendanceFinalizationNotificationPublisher {
    private final TelegramProperties properties;
    private final AdminSecurityProperties adminProperties;
    private final TelegramNotificationMapper mapper;

    public AttendanceFinalizationNotificationPublisher(
            TelegramProperties properties,
            AdminSecurityProperties adminProperties,
            TelegramNotificationMapper mapper) {
        this.properties = properties;
        this.adminProperties = adminProperties;
        this.mapper = mapper;
    }

    /** 연결된 활성 부서 관리자별로 하나의 멱등 outbox 작업을 만든다. */
    public void enqueueForFinalizedDay(long attendanceDayId) {
        if (!properties.enabled()) {
            return;
        }
        FinalizationNotificationData data = mapper.selectFinalizationData(attendanceDayId);
        if (data == null) {
            throw new IllegalStateException("finalized attendance day was not found");
        }
        String message = format(data, mapper.selectFinalizationMembers(attendanceDayId));
        for (long accountId : mapper.selectConnectedActiveDepartmentAdminAccountIds(
                data.departmentId())) {
            mapper.insertFinalizationOutbox(
                    attendanceDayId, data.departmentId(), accountId, message);
        }
    }

    private String format(
            FinalizationNotificationData data,
            List<FinalizationNotificationMember> members) {
        List<FinalizationNotificationMember> late = members.stream()
                .filter(member -> "LATE".equals(member.status())).toList();
        List<FinalizationNotificationMember> absent = members.stream()
                .filter(member -> "ABSENT".equals(member.status())).toList();
        StringBuilder text = new StringBuilder("출석 마감 완료\n\n")
                .append("부서: ").append(data.departmentName()).append('\n')
                .append("날짜: ").append(data.attendanceDate()).append('\n')
                .append("대상: ").append(data.targetCount()).append("명 / 정상: ")
                .append(data.presentCount()).append("명 / 지각: ").append(data.lateCount())
                .append("명 / 결석: ").append(data.absentCount()).append("명\n\n");
        Map<String, Long> duplicateNames = members.stream()
                .collect(Collectors.groupingBy(FinalizationNotificationMember::name, Collectors.counting()));
        appendSection(text, "지각", late, duplicateNames);
        text.append('\n');
        appendSection(text, "결석", absent, duplicateNames);
        String baseUrl = adminProperties.publicBaseUrl();
        if (baseUrl != null) {
            text.append("\n\n상세 확인\n")
                    .append(baseUrl).append("/admin/departments/")
                    .append(data.departmentId()).append("/attendance-days/")
                    .append(data.attendanceDayId());
        }
        return text.substring(0, Math.min(text.length(), 4096));
    }

    private void appendSection(
            StringBuilder text,
            String title,
            List<FinalizationNotificationMember> members,
            Map<String, Long> duplicateNames) {
        text.append(title).append('\n');
        if (members.isEmpty()) {
            text.append("• 없음");
            return;
        }
        List<FinalizationNotificationMember> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(FinalizationNotificationMember::name)
                .thenComparing(member -> member.birth() == null ? LocalDate.MAX : member.birth())
                .thenComparingLong(FinalizationNotificationMember::memberId));
        int max = properties.maxListedMembers();
        for (int index = 0; index < Math.min(max, sorted.size()); index++) {
            FinalizationNotificationMember member = sorted.get(index);
            text.append("• ").append(displayName(member, duplicateNames)).append('\n');
        }
        if (sorted.size() > max) {
            text.append("• 외 ").append(sorted.size() - max).append("명\n");
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) == '\n') {
            text.setLength(text.length() - 1);
        }
    }

    private static String displayName(
            FinalizationNotificationMember member,
            Map<String, Long> duplicateNames) {
        if (duplicateNames.getOrDefault(member.name(), 0L) < 2) {
            return member.name();
        }
        if (member.birth() == null) {
            return member.name() + " (ID " + member.memberId() + ")";
        }
        return member.name() + " (" + String.format("%02d", member.birth().getYear() % 100) + ")";
    }
}
