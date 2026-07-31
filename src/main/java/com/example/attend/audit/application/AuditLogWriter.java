package com.example.attend.audit.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.audit.infrastructure.mybatis.AuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 업무 변경과 같은 트랜잭션에 참여해 감사 행을 추가한다.
 *
 * <p>민감한 도메인 객체 전체를 직렬화하지 않고, 호출자가 action별 allowlist로 만든
 * 작은 Map만 받는다. 비밀번호·장치 키·전체 UID는 이 경계로 전달하면 안 된다.</p>
 */
@Component
public final class AuditLogWriter {

	private final AuditLogMapper mapper;
	private final ObjectMapper objectMapper;

	/**
	 * 감사 Mapper와 안전한 JSON 직렬화기를 주입받는다.
	 *
	 * @param mapper 감사 행 Mapper
	 * @param objectMapper Spring이 구성한 JSON 직렬화기
	 */
	public AuditLogWriter(AuditLogMapper mapper, ObjectMapper objectMapper) {
		this.mapper = mapper;
		this.objectMapper = objectMapper;
	}

	/**
	 * 인증 계정이 수행한 부서 업무 변경을 기록한다.
	 *
	 * @param departmentId 승인된 부서
	 * @param actor 인증 계정
	 * @param attendanceDayId 관련 출석일, 없으면 {@code null}
	 * @param action 감사 action
	 * @param targetType 변경 대상 종류
	 * @param targetId 변경 대상 식별 문자열
	 * @param beforeData 변경 전 allowlist, 없으면 {@code null}
	 * @param afterData 변경 후 allowlist, 없으면 {@code null}
	 * @param reason 관리자 사유, 필요 없으면 {@code null}
	 */
	public void writeAccount(
			long departmentId,
			AccountActor actor,
			Long attendanceDayId,
			String action,
			String targetType,
			String targetId,
			Map<String, ?> beforeData,
			Map<String, ?> afterData,
			String reason
	) {
		mapper.insertAccountAudit(
				departmentId,
				actor.accountId(),
				attendanceDayId,
				action,
				targetType,
				targetId,
				toJson(beforeData),
				toJson(afterData),
				reason);
	}

	/**
	 * 자동 마감 시스템 작업을 멱등 감사 키와 함께 기록한다.
	 *
	 * @param departmentId 마감 부서
	 * @param attendanceDayId 마감 출석일
	 * @param action 감사 action
	 * @param targetType 변경 대상 종류
	 * @param targetId 대상 식별 문자열
	 * @param afterData 생성 결과 allowlist
	 * @param idempotencyKey 동일 날짜 중복 감사를 막는 키
	 * @return 새 감사 행이 추가됐으면 1, 이미 있으면 0
	 */
	public int writeSystemOnce(
			long departmentId,
			long attendanceDayId,
			String action,
			String targetType,
			String targetId,
			Map<String, ?> afterData,
			String idempotencyKey
	) {
		return mapper.insertSystemAuditOnce(
				departmentId,
				attendanceDayId,
				action,
				targetType,
				targetId,
				toJson(afterData),
				idempotencyKey);
	}

	/**
	 * allowlist Map을 PostgreSQL jsonb 입력 문자열로 바꾼다.
	 *
	 * @param value 직렬화할 값, 없으면 {@code null}
	 * @return JSON 문자열 또는 {@code null}
	 */
	private String toJson(Map<String, ?> value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("audit data could not be serialized", exception);
		}
	}
}
