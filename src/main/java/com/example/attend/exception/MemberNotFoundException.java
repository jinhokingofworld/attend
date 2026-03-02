package com.example.attend.exception;

public class MemberNotFoundException extends RuntimeException {
    public MemberNotFoundException() {
        super("MemberNotFoundException");
    }
}
