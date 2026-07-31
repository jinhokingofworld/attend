package com.example.attend.organization.application;

/**
 * 기존 교사의 허용된 기본정보만 수정하는 명령이다.
 *
 * @param name 교사 이름
 * @param phone 선택 연락처
 */
public record UpdateTeacherCommand(String name, String phone) {

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
