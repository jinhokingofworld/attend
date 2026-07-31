package com.example.attend.common.error;

/**
 * 인증 계정이 대상 부서의 활성 관리자가 아닐 때 발생한다.
 */
public class DepartmentAccessDeniedException extends RuntimeException {

	/**
	 * 부서 존재 여부를 노출하지 않는 동일한 메시지로 예외를 만든다.
	 */
	public DepartmentAccessDeniedException() {
		super("department administrator authority is required");
	}
}
