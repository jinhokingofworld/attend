package com.example.attend.organization.api;

/**
 * attendance 모듈이 부서 활성 소속을 잠가 대상자 자격을 확인하는 경계다.
 */
public interface ActiveMembershipLookup {

	/**
	 * 승인되고 잠긴 부서에서 교사의 활성 소속을 잠근다.
	 *
	 * @return 활성 소속, 없으면 {@code null}
	 */
	ActiveMembership lockActive(long departmentId, long memberId);
}
