package com.example.attend.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class Attendance {
    private Long id;
    private Long memberId;
    private LocalDateTime attendTime;
    private LocalDate attendDate;
    private AttendStatus status;
    private String note;

    public Attendance(Long memberId, AttendStatus status) {
        this.memberId = memberId;
        this.status = status;
    }

    public Attendance(Long memberId, AttendStatus status, LocalDateTime attendTime, LocalDate attendDate) {
        this.memberId = memberId;
        this.status = status;
        this.attendTime = attendTime;
        this.attendDate = attendDate;
    }
}
