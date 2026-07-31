package com.example.attend.access.domain;

/**
 * 전체 시스템 범위에서 부여할 수 있는 계정 역할이다.
 *
 * <p>부서 관리 권한은 {@code account_department_role}에서 별도로 관리한다.
 * 따라서 이 열거형에 부서 관리자 역할을 추가해서는 안 된다.</p>
 */
public enum AccountSystemRole {

	/**
	 * 부서와 관리자 계정을 생성하고 권한을 배정하는 시스템 관리자다.
	 */
	SYSTEM_ADMIN
}
