package com.example.attend.exception;

public class AlreadyAttendedException extends RuntimeException {
    public AlreadyAttendedException() {
        super("AlreadyAttendedException");
    }
}
