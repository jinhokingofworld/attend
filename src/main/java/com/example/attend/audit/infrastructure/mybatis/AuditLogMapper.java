package com.example.attend.audit.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * append-only 감사 행을 저장하는 MyBatis Mapper다.
 */
@Mapper
public interface AuditLogMapper {

	/**
	 * 인증 계정의 감사 행을 추가한다.
	 */
	void insertAccountAudit(
			@Param("departmentId") long departmentId,
			@Param("actorAccountId") long actorAccountId,
			@Param("attendanceDayId") Long attendanceDayId,
			@Param("action") String action,
			@Param("targetType") String targetType,
			@Param("targetId") String targetId,
			@Param("beforeData") String beforeData,
			@Param("afterData") String afterData,
			@Param("reason") String reason);

	/**
	 * 멱등 키가 없는 경우에만 시스템 감사 행을 추가한다.
	 *
	 * @return 추가된 행 수
	 */
	int insertSystemAuditOnce(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId,
			@Param("action") String action,
			@Param("targetType") String targetType,
			@Param("targetId") String targetId,
			@Param("afterData") String afterData,
			@Param("idempotencyKey") String idempotencyKey);
}
