package com.example.attend.organization.application;

import java.time.LocalDate;

/**
 * 기존 교사의 허용된 기본정보만 수정하는 명령이다.
 *
 * @param name 교사 이름
 * @param phone 선택 연락처
 * @param birth 필수 생년월일. 생일 관리와 만 나이 계산에 사용한다
 */
public record UpdateTeacherCommand(String name, String phone, LocalDate birth) {

	/**
	 * 교사 추가와 같은 문자열 정규화 규칙을 적용한다.
	 */
	public UpdateTeacherCommand {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("teacher name must not be blank");
		}
		name = name.trim();
		if (name.length() > 255) {
			throw new IllegalArgumentException("teacher name must not exceed 255 characters");
		}
		if (birth == null) {
			throw new IllegalArgumentException("생년월일은 필수입니다.");
		}
		if (phone != null) {
			phone = phone.trim();
			if (phone.isEmpty()) {
				phone = null;
			} else if (phone.length() > 255) {
				throw new IllegalArgumentException("phone must not exceed 255 characters");
			}
		}
	}
}
