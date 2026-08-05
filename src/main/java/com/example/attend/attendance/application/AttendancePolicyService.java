package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.domain.AttendanceBand;
import com.example.attend.attendance.domain.AttendancePolicy;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.PolicyVersionRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 정책 초안을 저장하고 전체 검증을 통과한 버전만 발행한다.
 */
@Service
public class AttendancePolicyService {

	private final DepartmentAuthorization authorization;
	private final AdminWriteAuthorization writeAuthorization;
	private final DepartmentLock departmentLock;
	private final AttendancePolicyMapper mapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 정책 유스케이스의 협력 객체를 주입받는다.
	 */
	public AttendancePolicyService(
			AdminWriteAuthorization writeAuthorization,
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			AttendancePolicyMapper mapper,
			AuditLogWriter auditLogWriter,
			Clock clock
	) {
		this.writeAuthorization = writeAuthorization;
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.mapper = mapper;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
	}

	/**
	 * 부서 행 잠금으로 version 번호를 직렬화한 뒤 초안을 만든다.
	 *
	 * @return 생성된 정책 버전 식별자
	 */
	@Transactional
	public long createDraft(
			AccountActor actor,
			long departmentId,
			PolicyDraftCommand command
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		int versionNo = mapper.selectNextVersionNo(departmentId);
		long policyId = mapper.insertDraft(
				departmentId,
				versionNo,
				command.name(),
				command.checkInStartTime(),
				actor.accountId());
		insertBands(policyId, command.bands());
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"POLICY_DRAFT_CREATED",
				"ATTENDANCE_POLICY_VERSION",
				Long.toString(policyId),
				null,
				Map.of("versionNo", versionNo, "bandCount", command.bands().size()),
				null);
		return policyId;
	}

	/**
	 * 잠근 DRAFT 정책의 편집 가능한 값과 구간을 전부 교체한다.
	 */
	@Transactional
	public void replaceDraft(
			AccountActor actor,
			long departmentId,
			long policyVersionId,
			PolicyDraftCommand command
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		PolicyVersionRow draft = requireDraft(departmentId, policyVersionId);
		List<AttendanceBand> previousBands = mapper.selectBands(policyVersionId);
		requireSingleUpdate(mapper.updateDraft(
				departmentId,
				policyVersionId,
				command.name(),
				command.checkInStartTime()));
		mapper.deleteDraftBands(policyVersionId);
		insertBands(policyVersionId, command.bands());
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"POLICY_DRAFT_REPLACED",
				"ATTENDANCE_POLICY_VERSION",
				Long.toString(policyVersionId),
				policyDraftAuditData(
						draft.name(),
						draft.checkInStartTime().toString(),
						previousBands.stream()
								.map(AttendancePolicyService::bandAuditData)
								.toList()),
				policyDraftAuditData(
						command.name(),
						command.checkInStartTime().toString(),
						command.bands().stream()
								.map(AttendancePolicyService::bandAuditData)
								.toList()),
				null);
	}

	/**
	 * 구간 전체를 순수 도메인 규칙으로 검증한 뒤 발행 상태와 감사를 함께 저장한다.
	 *
	 * @return 발행된 불변 정책
	 */
	@Transactional
	public AttendancePolicy publish(
			AccountActor actor,
			long departmentId,
			long policyVersionId
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		PolicyVersionRow draft = requireDraft(departmentId, policyVersionId);
		List<AttendanceBand> bands = mapper.selectBands(policyVersionId);
		AttendancePolicy policy = new AttendancePolicy(
				policyVersionId,
				draft.checkInStartTime(),
				bands);
		requireSingleUpdate(mapper.publish(
				departmentId,
				policyVersionId,
				actor.accountId(),
				clock.instant()));
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"POLICY_PUBLISHED",
				"ATTENDANCE_POLICY_VERSION",
				Long.toString(policyVersionId),
				Map.of("status", "DRAFT"),
				Map.of("status", "PUBLISHED", "bandCount", bands.size()),
				null);
		return policy;
	}

	/**
	 * 정책 command의 공통 부서 인가와 부서 행 잠금을 수행한다.
	 */
	private void authorizeAndLock(AccountActor actor, long departmentId) {
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
	}

	/**
	 * 발행되지 않은 자기 부서 초안만 잠가 반환한다.
	 */
	private PolicyVersionRow requireDraft(long departmentId, long policyVersionId) {
		PolicyVersionRow draft = mapper.lockDraft(departmentId, policyVersionId);
		if (draft == null) {
			throw new ResourceNotFoundException("draft attendance policy");
		}
		return draft;
	}

	/**
	 * 입력 순서를 보존해 초안 구간을 한 행씩 저장한다.
	 */
	private void insertBands(long policyVersionId, List<PolicyBandInput> bands) {
		for (PolicyBandInput band : bands) {
			mapper.insertBand(policyVersionId, band);
		}
	}

	private static Map<String, Object> policyDraftAuditData(
			String name,
			String checkInStartTime,
			List<Map<String, Object>> bands) {
		return Map.of(
				"name", name,
				"checkInStartTime", checkInStartTime,
				"bandCount", bands.size(),
				"bands", bands);
	}

	private static Map<String, Object> bandAuditData(AttendanceBand band) {
		return bandAuditData(
				band.sequenceNo(),
				band.label(),
				band.parentStatus().name(),
				band.upperTime().toString());
	}

	private static Map<String, Object> bandAuditData(PolicyBandInput band) {
		return bandAuditData(
				band.sequenceNo(),
				band.label(),
				band.parentStatus().name(),
				band.upperTime().toString());
	}

	private static Map<String, Object> bandAuditData(
			int sequenceNo,
			String label,
			String parentStatus,
			String upperTime) {
		return Map.of(
				"sequenceNo", sequenceNo,
				"label", label,
				"parentStatus", parentStatus,
				"upperTime", upperTime);
	}

	/**
	 * DRAFT 조건부 UPDATE의 동시 변경 여부를 확인한다.
	 */
	private static void requireSingleUpdate(int rows) {
		if (rows != 1) {
			throw new BusinessRuleException("policy state changed concurrently");
		}
	}
}
