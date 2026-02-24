package com.example.attend.entity;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Builder
@Data
public class AttendanceLog {
    @NotNull private String uid;
    private Long memberId;
    @NotNull LogResult result;      // SUCCESS/FAIL
    private String failType;    // UNKNOWN_UID 등
    private String message;

    public static AttendanceLog success(String uid, Long memberId) {
        return new AttendanceLog(uid, memberId, LogResult.SUCCESS, null, null);
    }

    // 실패용 (안전)
    public static AttendanceLog fail(String uid, Long memberId, String failType, String message) {
        return new AttendanceLog(uid, memberId, LogResult.FAIL, failType, message);
    }
}
