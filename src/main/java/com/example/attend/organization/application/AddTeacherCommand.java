package com.example.attend.organization.application;

import com.example.attend.organization.domain.NfcUid;

import java.time.LocalDate;

/**
 * 신규 교사와 선택적인 NFC 카드를 함께 등록하는 명령이다.
 *
 * @param name 교사 이름
 * @param phone 선택 연락처
 * @param birth 필수 생년월일. 생일 관리와 화면의 만 나이 계산에 사용한다
 * @param cardUid 함께 연결할 카드 UID, 카드 없이 등록하면 {@code null}
 */
public record AddTeacherCommand(
		String name,
		String phone,
		LocalDate birth,
		NfcUid cardUid
) {

	/**
	 * 화면 입력의 공백과 길이를 정규화한다.
	 */
	public AddTeacherCommand {
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
