package com.example.attend.access.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 계정의 활성 부서 관리자 권한을 부서 범위로 확인한다.
 */
@Mapper
public interface DepartmentAuthorizationMapper {

	/**
	 * 계정이 현재 활성 시스템 관리자인지 계산한다.
	 *
	 * @param accountId 인증 계정 식별자
	 * @return 권한이 있으면 1, 없으면 0
	 */
	int countActiveSystemAdmin(@Param("accountId") long accountId);

	/**
	 * 활성 부서와 아직 회수되지 않은 관리자 역할이 함께 존재하는지 계산한다.
	 *
	 * @param accountId 인증 계정 식별자
	 * @param departmentId 대상 부서 식별자
	 * @return 권한이 있으면 1, 없으면 0
	 */
	int countActiveDepartmentAdmin(
			@Param("accountId") long accountId,
			@Param("departmentId") long departmentId);
}
