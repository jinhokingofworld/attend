package com.example.attend.organization.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.organization.api.DepartmentLock;
import com.example.attend.organization.domain.CardStatus;
import com.example.attend.organization.infrastructure.mybatis.CardRow;
import com.example.attend.organization.infrastructure.mybatis.OrganizationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 신규 교사, 활성 소속과 선택적인 NFC 카드 연결을 원자적으로 생성한다.
 */
@Service
public class TeacherRosterService {

	private final DepartmentAuthorization authorization;
	private final AdminWriteAuthorization writeAuthorization;
	private final DepartmentLock departmentLock;
	private final OrganizationMapper mapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 교사 등록에 필요한 권한·잠금·저장 경계를 주입받는다.
	 */
	public TeacherRosterService(
			AdminWriteAuthorization writeAuthorization,
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			OrganizationMapper mapper,
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
	 * 교사와 소속을 만들고 카드가 있으면 같은 트랜잭션에서 연결한다.
	 *
	 * @param actor 인증 관리자
	 * @param departmentId 대상 부서
	 * @param command 정규화된 교사 등록 명령
	 * @return 생성된 업무 식별자
	 */
	@Transactional
	public TeacherRegistrationResult addTeacher(
			AccountActor actor,
			long departmentId,
			AddTeacherCommand command
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		Instant occurredAt = clock.instant();

		long memberId = mapper.insertMember(command.name(), command.phone());
		long membershipId = mapper.insertMembership(
				departmentId,
				memberId,
				actor.accountId(),
				occurredAt);

		Long cardId = null;
		Long assignmentId = null;
		if (command.cardUid() != null) {
			mapper.insertAvailableCardIfAbsent(command.cardUid().value());
			CardRow card = mapper.lockCardByUid(command.cardUid().value());
			if (card == null || !CardStatus.AVAILABLE.name().equals(card.status())) {
				throw new BusinessRuleException("card is not available");
			}
			requireSingleUpdate(mapper.updateCardStatus(
					card.id(),
					CardStatus.AVAILABLE.name(),
					CardStatus.ACTIVE.name()), "card state changed concurrently");
			assignmentId = mapper.insertCardAssignment(
					card.id(),
					departmentId,
					membershipId,
					memberId,
					actor.accountId(),
					occurredAt);
			cardId = card.id();
		}

		Map<String, Object> afterData = new LinkedHashMap<>();
		afterData.put("memberId", memberId);
		afterData.put("membershipId", membershipId);
		afterData.put("cardConnected", cardId != null);
		if (command.cardUid() != null) {
			afterData.put("maskedUid", command.cardUid().masked());
		}
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"TEACHER_ADDED",
				"MEMBER",
				Long.toString(memberId),
				null,
				afterData,
				null);

		return new TeacherRegistrationResult(
				memberId,
				membershipId,
				cardId,
				assignmentId);
	}

	/**
	 * 활성 소속으로 부서 범위를 증명한 교사의 이름과 연락처만 수정한다.
	 */
	@Transactional
	public void updateTeacher(
			AccountActor actor,
			long departmentId,
			long memberId,
			UpdateTeacherCommand command
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		if (mapper.lockActiveMembership(departmentId, memberId) == null
				|| mapper.updateTeacher(
						departmentId,
						memberId,
						command.name(),
						command.phone()) != 1) {
			throw new BusinessRuleException("active teacher could not be updated");
		}
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"TEACHER_UPDATED",
				"MEMBER",
				Long.toString(memberId),
				null,
				Map.of("nameChanged", true, "phoneChanged", true),
				null);
	}

	/**
	 * 상태 기반 UPDATE가 정확히 한 행을 바꿨는지 확인한다.
	 */
	private static void requireSingleUpdate(int updatedRows, String message) {
		if (updatedRows != 1) {
			throw new BusinessRuleException(message);
		}
	}
}
