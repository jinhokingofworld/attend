package com.example.attend.organization.api;

/**
 * 여러 모듈이 동일한 순서로 부서 행을 잠그기 위한 조직 모듈 경계다.
 */
public interface DepartmentLock {

	/**
	 * 활성 부서 행을 {@code FOR UPDATE}로 잠근다.
	 *
	 * @param departmentId 잠글 부서 식별자
	 */
	void lockActive(long departmentId);
}
