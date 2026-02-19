package com.example.attend.exception;

public class AlreadyAttendedException extends RuntimeException {
    public AlreadyAttendedException() {
        super("이미 출석 처리되었습니다.");
    }
}
