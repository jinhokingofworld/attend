package com.example.attend.organization.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.MembershipClosure;
import com.example.attend.organization.api.MembershipClosureResult;
import com.example.attend.organization.domain.CardDisposition;
import com.example.attend.organization.domain.CardStatus;
import com.example.attend.organization.infrastructure.mybatis.CardAssignmentRow;
import com.example.attend.organization.infrastructure.mybatis.MembershipRow;
import com.example.attend.organization.infrastructure.mybatis.OrganizationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 바깥 부서 제외 트랜잭션 안에서 조직 소유 행을 종료한다.
 */
@Component
public class OrganizationMembershipClosure implements MembershipClosure {

	private final OrganizationMapper mapper;

	/**
	 * 조직 Mapper를 주입받는다.
	 *
	 * @param mapper 조직 Mapper
	 */
	public OrganizationMembershipClosure(OrganizationMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public MembershipClosureResult close(
			long departmentId,
			long memberId,
			AccountActor actor,
			CardDisposition disposition,
			String reason,
			Instant occurredAt
	) {
		MembershipRow membership = mapper.lockActiveMembership(departmentId, memberId);
		if (membership == null) {
			throw new ResourceNotFoundException("active membership");
		}

		CardAssignmentRow assignment = mapper.lockActiveAssignment(departmentId, memberId);
		Long cardId = null;
		CardStatus cardStatus = null;
		if (assignment != null) {
			requireSingleUpdate(mapper.endCardAssignment(
					assignment.assignmentId(),
					actor.accountId(),
					occurredAt,
					reason));
			cardStatus = disposition.targetStatus();
			requireSingleUpdate(mapper.updateCardStatus(
					assignment.cardId(),
					CardStatus.ACTIVE.name(),
					cardStatus.name()));
			cardId = assignment.cardId();
		}

		requireSingleUpdate(mapper.endMembership(
				membership.id(),
				actor.accountId(),
				occurredAt,
				reason));
		mapper.deactivateMemberWithoutActiveMembership(memberId);
		return new MembershipClosureResult(membership.id(), cardId, cardStatus);
	}

	/**
	 * 바깥 트랜잭션이 확인한 상태가 그대로인지 영향 행 수로 검증한다.
	 */
	private static void requireSingleUpdate(int rows) {
		if (rows != 1) {
			throw new BusinessRuleException("membership state changed concurrently");
		}
	}
}
