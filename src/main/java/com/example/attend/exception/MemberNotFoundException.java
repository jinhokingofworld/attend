package com.example.attend.exception;

public class MemberNotFoundException extends RuntimeException {
    public MemberNotFoundException() {
        super("해당 UID를 가진 사람을 찾을 수 없습니다.");
    }
}
