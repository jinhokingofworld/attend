package com.example.attend.notification.domain;

import java.time.LocalDate;

/** 지각·결석 목록에서 개인정보를 최소화해 표시할 구성원이다. */
public record FinalizationNotificationMember(
        long memberId,
        String name,
        LocalDate birth,
        String status) {
}
