package com.example.attend.organization.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import com.example.attend.organization.domain.CardDisposition;
import com.example.attend.organization.domain.CardStatus;
import com.example.attend.organization.domain.NfcUid;
import com.example.attend.organization.infrastructure.mybatis.CardAssignmentRow;
import com.example.attend.organization.infrastructure.mybatis.CardRow;
import com.example.attend.organization.infrastructure.mybatis.MembershipRow;
import com.example.attend.organization.infrastructure.mybatis.OrganizationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 기존 교사의 카드 연결·교체·해제를 이력과 상태 전이로 처리한다.
 */
@Service
public class CardManagementService {

	private final DepartmentAuthorization authorization;
	private final AdminWriteAuthorization writeAuthorization;
	private final DepartmentLock departmentLock;
	private final OrganizationMapper mapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 카드 유스케이스의 협력 객체를 주입받는다.
	 */
	public CardManagementService(
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
	 * 활성 소속 교사에게 연결 가능한 카드를 연결한다.
	 */
	@Transactional
	public CardManagementResult connect(
			AccountActor actor,
			long departmentId,
			long memberId,
			NfcUid uid
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		return connectAfterLock(actor, departmentId, memberId, uid);
	}

	/**
	 * 카드 등록함 이벤트의 원본 UID를 브라우저에 노출하지 않고 교사에게 연결한다.
	 *
	 * @param actor 인증 관리자
	 * @param departmentId 부서 식별자
	 * @param memberId 연결할 교사 식별자
	 * @param eventId 미등록 카드 태깅 이벤트 식별자
	 * @return 생성된 카드 연결 결과
	 */
	@Transactional
	public CardManagementResult connectFromInbox(
			AccountActor actor,
			long departmentId,
			long memberId,
			long eventId
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		String uid = mapper.selectAssignableTagEventUid(departmentId, eventId);
		if (uid == null) {
			throw new ResourceNotFoundException("assignable card event");
		}
		return connectAfterLock(
				actor, departmentId, memberId, new NfcUid(uid));
	}

	/** 이미 인가·잠근 부서에서 공통 카드 연결 규칙을 실행한다. */
	private CardManagementResult connectAfterLock(
			AccountActor actor,
			long departmentId,
			long memberId,
			NfcUid uid
	) {
		MembershipRow membership = requireMembership(departmentId, memberId);
		if (mapper.lockActiveAssignment(departmentId, memberId) != null) {
			throw new BusinessRuleException("member already has an active card");
		}

		Instant occurredAt = clock.instant();
		CardRow card = activateAvailableCard(uid);
		long assignmentId = mapper.insertCardAssignment(
				card.id(),
				departmentId,
				membership.id(),
				memberId,
				actor.accountId(),
				occurredAt);
		auditCard(actor, departmentId, memberId, "CARD_CONNECTED", card.id(),
				null, CardStatus.ACTIVE, uid.masked(), null);
		return new CardManagementResult(card.id(), assignmentId, CardStatus.ACTIVE);
	}

	/**
	 * 활성 카드를 종료하고 새 카드를 같은 트랜잭션에서 연결한다.
	 */
	@Transactional
	public CardManagementResult replace(
			AccountActor actor,
			long departmentId,
			long memberId,
			NfcUid newUid,
			String reason
	) {
		writeAuthorization.requireEnabled();
		reason = requireReason(reason);
		authorizeAndLock(actor, departmentId);
		MembershipRow membership = requireMembership(departmentId, memberId);
		CardAssignmentRow previous = requireAssignment(departmentId, memberId);
		Instant occurredAt = clock.instant();

		CardRow nextCard = activateAvailableCard(newUid);
		if (nextCard.id() == previous.cardId()) {
			throw new BusinessRuleException("replacement card must be different");
		}
		endAssignment(previous, actor, occurredAt, reason, CardDisposition.AVAILABLE);
		long assignmentId = mapper.insertCardAssignment(
				nextCard.id(),
				departmentId,
				membership.id(),
				memberId,
				actor.accountId(),
				occurredAt);
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"CARD_REPLACED",
				"MEMBER",
				Long.toString(memberId),
				Map.of(
						"cardId", previous.cardId(),
						"status", CardStatus.ACTIVE.name()),
				Map.of(
						"previousCardId", previous.cardId(),
						"previousCardStatus", CardStatus.AVAILABLE.name(),
						"newCardId", nextCard.id(),
						"newCardStatus", CardStatus.ACTIVE.name(),
						"maskedNewUid", newUid.masked()),
				reason);
		return new CardManagementResult(nextCard.id(), assignmentId, CardStatus.ACTIVE);
	}

	/**
	 * 활성 연결을 종료하고 카드에 회수·분실·폐기 상태를 적용한다.
	 */
	@Transactional
	public CardManagementResult disconnect(
			AccountActor actor,
			long departmentId,
			long memberId,
			CardDisposition disposition,
			String reason
	) {
		writeAuthorization.requireEnabled();
		reason = requireReason(reason);
		if (disposition == null) {
			throw new IllegalArgumentException("card disposition must not be null");
		}
		authorizeAndLock(actor, departmentId);
		requireMembership(departmentId, memberId);
		CardAssignmentRow assignment = requireAssignment(departmentId, memberId);
		Instant occurredAt = clock.instant();
		endAssignment(assignment, actor, occurredAt, reason, disposition);
		CardStatus target = disposition.targetStatus();
		auditCard(actor, departmentId, memberId, "CARD_DISCONNECTED",
				assignment.cardId(), CardStatus.ACTIVE, target, null, reason);
		return new CardManagementResult(
				assignment.cardId(),
				assignment.assignmentId(),
				target);
	}

	/**
	 * 외부 command가 공통으로 거쳐야 하는 부서 인가와 첫 행 잠금을 수행한다.
	 */
	private void authorizeAndLock(AccountActor actor, long departmentId) {
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
	}

	/**
	 * 다른 부서의 존재 여부를 노출하지 않고 활성 소속을 반환한다.
	 */
	private MembershipRow requireMembership(long departmentId, long memberId) {
		MembershipRow membership = mapper.lockActiveMembership(departmentId, memberId);
		if (membership == null) {
			throw new ResourceNotFoundException("active membership");
		}
		return membership;
	}

	/**
	 * 교사의 현재 활성 카드 연결을 잠그거나 범위 내 없음으로 처리한다.
	 */
	private CardAssignmentRow requireAssignment(long departmentId, long memberId) {
		CardAssignmentRow assignment = mapper.lockActiveAssignment(departmentId, memberId);
		if (assignment == null) {
			throw new ResourceNotFoundException("active card assignment");
		}
		return assignment;
	}

	/**
	 * 새 UID를 AVAILABLE로 준비한 뒤 잠그고 ACTIVE로 전환한다.
	 */
	private CardRow activateAvailableCard(NfcUid uid) {
		mapper.insertAvailableCardIfAbsent(uid.value());
		CardRow card = mapper.lockCardByUid(uid.value());
		if (card == null || !CardStatus.AVAILABLE.name().equals(card.status())) {
			throw new BusinessRuleException("card is not available");
		}
		requireSingleUpdate(mapper.updateCardStatus(
				card.id(),
				CardStatus.AVAILABLE.name(),
				CardStatus.ACTIVE.name()));
		return card;
	}

	/**
	 * 연결 종료 metadata와 카드 상태 전이를 분리되지 않게 적용한다.
	 */
	private void endAssignment(
			CardAssignmentRow assignment,
			AccountActor actor,
			Instant occurredAt,
			String reason,
			CardDisposition disposition
	) {
		requireSingleUpdate(mapper.endCardAssignment(
				assignment.assignmentId(),
				actor.accountId(),
				occurredAt,
				reason));
		requireSingleUpdate(mapper.updateCardStatus(
				assignment.cardId(),
				CardStatus.ACTIVE.name(),
				disposition.targetStatus().name()));
	}

	/**
	 * 전체 UID를 제외한 카드 변경 allowlist만 감사 로그에 전달한다.
	 */
	private void auditCard(
			AccountActor actor,
			long departmentId,
			long memberId,
			String action,
			long cardId,
			CardStatus beforeStatus,
			CardStatus afterStatus,
			String maskedUid,
			String reason
	) {
		Map<String, Object> before = beforeStatus == null
				? null
				: Map.of("cardId", cardId, "status", beforeStatus.name());
		Map<String, Object> after = maskedUid == null
				? Map.of("cardId", cardId, "status", afterStatus.name())
				: Map.of(
						"cardId", cardId,
						"status", afterStatus.name(),
						"maskedUid", maskedUid);
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				action,
				"MEMBER",
				Long.toString(memberId),
				before,
				after,
				reason);
	}

	/**
	 * 카드 위험 작업의 필수 사유를 공통 형식으로 정규화한다.
	 */
	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		reason = reason.trim();
		if (reason.length() > 500) {
			throw new IllegalArgumentException("reason must not exceed 500 characters");
		}
		return reason;
	}

	/**
	 * 낙관적인 상태 조건이 한 행에만 적용됐는지 확인한다.
	 */
	private static void requireSingleUpdate(int rows) {
		if (rows != 1) {
			throw new BusinessRuleException("card state changed concurrently");
		}
	}
}
