package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.infrastructure.mybatis.DepartmentAuthorizationMapper;
import com.example.attend.common.error.SystemAccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 세션에 남은 역할이 아니라 DB의 현재 계정 상태로 시스템 권한을 판정한다.
 */
@Component
public final class DatabaseSystemAuthorization implements SystemAuthorization {

	private final DepartmentAuthorizationMapper mapper;

	/**
	 * 현재 계정 역할을 조회할 Mapper를 주입받는다.
	 *
	 * @param mapper 권한 Mapper
	 */
	public DatabaseSystemAuthorization(DepartmentAuthorizationMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void requireSystemAdmin(AccountActor actor) {
		if (mapper.countActiveSystemAdmin(actor.accountId()) != 1) {
			throw new SystemAccessDeniedException();
		}
	}
}
