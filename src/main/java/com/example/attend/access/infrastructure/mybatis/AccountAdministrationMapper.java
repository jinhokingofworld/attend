package com.example.attend.access.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 시스템 관리자 계정·부서·역할과 일회용 token을 저장하고 조회한다.
 */
@Mapper
public interface AccountAdministrationMapper {

	/** 시스템 관리용 부서 목록을 조회한다. */
	List<Map<String, Object>> selectDepartments();

	/** 한 부서의 시스템 관리용 요약을 조회한다. */
	Map<String, Object> selectDepartment(@Param("departmentId") long departmentId);

	/** 부서를 추가하고 생성 ID를 반환한다. */
	long insertDepartment(@Param("name") String name);

	/** 부서 이름을 변경한다. */
	int updateDepartmentName(
			@Param("departmentId") long departmentId,
			@Param("name") String name);

	/** 부서를 논리적으로 비활성화한다. */
	int deactivateDepartment(@Param("departmentId") long departmentId);

	/** 비활성 부서를 다시 업무 가능 상태로 돌린다. */
	int reactivateDepartment(@Param("departmentId") long departmentId);

	/** 부서의 활성 관리자 계정 목록을 조회한다. */
	List<Map<String, Object>> selectDepartmentAdministrators(
			@Param("departmentId") long departmentId);

	/** 부서의 장치 상태 요약 목록을 조회한다. */
	List<Map<String, Object>> selectDepartmentDevices(
			@Param("departmentId") long departmentId);

	/** 시스템 관리용 계정 목록을 조회한다. */
	List<Map<String, Object>> selectAccounts();

	/** 계정 한 건을 조회한다. */
	AccountAdministrationRow selectAccount(@Param("accountId") long accountId);

	/** 대소문자를 무시한 사용자명으로 계정을 조회한다. */
	AccountAdministrationRow selectAccountByUsername(@Param("username") String username);

	/** 상태 변경할 계정 행을 잠근다. */
	AccountAdministrationRow lockAccount(@Param("accountId") long accountId);

	/** 비밀번호 없는 초대 대기 계정을 추가한다. */
	long insertPendingAccount(
			@Param("username") String username,
			@Param("systemRole") String systemRole);

	/** 대상 외 활성 시스템 관리자 수를 계산한다. */
	int countOtherActiveSystemAdmins(@Param("accountId") long accountId);

	/** 활성·대기 계정을 비활성화한다. */
	int disableAccount(@Param("accountId") long accountId);

	/** 비활성 계정을 비밀번호 상태에 맞게 되돌린다. */
	int enableAccount(@Param("accountId") long accountId);

	/** 계정의 활성 부서 역할을 조회한다. */
	List<Map<String, Object>> selectAccountDepartmentRoles(
			@Param("accountId") long accountId);

	/** 동일한 활성 부서 역할이 있는지 계산한다. */
	int countActiveRole(
			@Param("accountId") long accountId,
			@Param("departmentId") long departmentId);

	/** 부서 관리자 역할 이력을 시작한다. */
	Long insertDepartmentRole(
			@Param("accountId") long accountId,
			@Param("departmentId") long departmentId,
			@Param("actorAccountId") long actorAccountId,
			@Param("assignedAt") Instant assignedAt);

	/** 메일로 전달할 부서 관리자 초대 작업을 저장한다. */
	long insertDepartmentAdminInvitationOutbox(
			@Param("accountId") long accountId,
			@Param("departmentId") long departmentId,
			@Param("issuerAccountId") long issuerAccountId,
			@Param("deliveryType") String deliveryType,
			@Param("recipientEmail") String recipientEmail);

	/** 부서 상세 화면의 최근 초대 전달 상태를 조회한다. */
	List<Map<String, Object>> selectDepartmentAdminInvitationOutbox(
			@Param("departmentId") long departmentId);

	/** 활성 부서 관리자 역할 이력을 종료한다. */
	int revokeDepartmentRole(
			@Param("accountId") long accountId,
			@Param("departmentId") long departmentId,
			@Param("revokedAt") Instant revokedAt);

	/** 계정이 선택할 수 있는 부서 작업 공간을 조회한다. */
	List<Map<String, Object>> selectWorkspaces(
			@Param("accountId") long accountId);

	/**
	 * 개인정보 없는 시스템 운영 집계를 조회한다.
	 *
	 * @return 운영 상태 집계
	 */
	Map<String, Object> selectSystemOperations();

	/**
	 * 시스템 범위 action allowlist의 최근 감사 이력을 조회한다.
	 *
	 * @return 최근 시스템 감사 목록
	 */
	List<Map<String, Object>> selectSystemAudit();

	/** 같은 계정·목적의 미종료 token을 모두 무효화한다. */
	int revokeActiveTokens(
			@Param("accountId") long accountId,
			@Param("purpose") String purpose,
			@Param("revokedAt") Instant revokedAt);

	/** 원문 대신 HMAC hash가 든 token 행을 추가한다. */
	long insertCredentialToken(
			@Param("accountId") long accountId,
			@Param("purpose") String purpose,
			@Param("tokenHash") String tokenHash,
			@Param("issuerAccountId") long issuerAccountId,
			@Param("issuedAt") Instant issuedAt,
			@Param("expiresAt") Instant expiresAt);

	/** 소비할 token과 계정을 함께 잠근다. */
	CredentialTokenRow lockCredentialToken(
			@Param("tokenHash") String tokenHash,
			@Param("purpose") String purpose);

	/** 초대 계정에 비밀번호를 저장하고 활성화한다. */
	int activateInvitedAccount(
			@Param("accountId") long accountId,
			@Param("passwordHash") String passwordHash,
			@Param("changedAt") Instant changedAt);

	/** 활성 계정의 비밀번호를 재설정한다. */
	int resetActiveAccountPassword(
			@Param("accountId") long accountId,
			@Param("passwordHash") String passwordHash,
			@Param("changedAt") Instant changedAt);

	/** 성공적으로 사용한 token의 소비 시각을 기록한다. */
	int consumeCredentialToken(
			@Param("tokenId") long tokenId,
			@Param("consumedAt") Instant consumedAt);

	/** 로그인 계정 본인의 비밀번호를 변경한다. */
	int updateOwnPassword(
			@Param("accountId") long accountId,
			@Param("passwordHash") String passwordHash,
			@Param("changedAt") Instant changedAt);
}
