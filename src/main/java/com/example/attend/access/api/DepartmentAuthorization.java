package com.example.attend.access.api;

/**
 * application service가 부서 관리자 권한을 확인할 때 의존하는 최소 인가 계약이다.
 *
 * <p>M2에서는 조직·출석 유스케이스가 임시로 권한 검사를 생략하지 않도록 이 경계만 먼저
 * 정의한다. 실제 계정과 {@code account_department_role}을 조회하는 구현은 M3에서
 * Spring Security principal과 함께 연결한다.</p>
 */
public interface DepartmentAuthorization {

	/**
	 * 요청 계정이 대상 부서의 활성 관리자인지 확인한다.
	 *
	 * <p>권한이 없거나 대상 부서를 공개할 수 없으면 구현체가 인가 예외를 던져야 한다.
	 * 서비스는 검증되지 않은 {@code departmentId}를 Mapper에 전달해서는 안 된다.</p>
	 *
	 * @param actor 인증 계정
	 * @param departmentId 관리하려는 부서의 DB 식별자
	 */
	void requireDepartmentAdmin(AccountActor actor, long departmentId);
}
