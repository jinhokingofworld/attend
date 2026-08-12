package com.example.attend.operations.application;

/** 마감 재시도 소진 outbox가 현재 transaction에 저장됐음을 알리는 내부 이벤트다. */
public record FinalizationOperationalIncidentCreated(long eventId) {

    public FinalizationOperationalIncidentCreated {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
    }
}
