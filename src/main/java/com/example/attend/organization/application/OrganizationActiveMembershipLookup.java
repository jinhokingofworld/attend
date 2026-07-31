package com.example.attend.organization.application;

import com.example.attend.organization.api.ActiveMembership;
import com.example.attend.organization.api.ActiveMembershipLookup;
import com.example.attend.organization.infrastructure.mybatis.MembershipRow;
import com.example.attend.organization.infrastructure.mybatis.OrganizationMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * organization Mapper를 외부에 노출하지 않고 활성 소속 잠금을 제공한다.
 */
@Component
public class OrganizationActiveMembershipLookup implements ActiveMembershipLookup {

	private final OrganizationMapper mapper;

	/**
	 * 조직 Mapper를 주입받는다.
	 *
	 * @param mapper 조직 Mapper
	 */
	public OrganizationActiveMembershipLookup(OrganizationMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public ActiveMembership lockActive(long departmentId, long memberId) {
		MembershipRow row = mapper.lockActiveMembership(departmentId, memberId);
		return row == null ? null : new ActiveMembership(row.id(), row.joinedAt());
	}
}
