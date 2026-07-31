package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.access.infrastructure.mybatis.DepartmentAuthorizationMapper;
import com.example.attend.common.error.DepartmentAccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * DB의 활성 역할을 조회해 M2 application service의 부서 경계를 강제한다.
 *
 * <p>M3에서는 Spring Security principal이 {@link AccountActor}를 만들지만, 권한의
 * 최종 근거는 계속 DB의 활성 {@code account_department_role} 행이다.</p>
 */
@Component
public final class DatabaseDepartmentAuthorization implements DepartmentAuthorization {

	private final DepartmentAuthorizationMapper mapper;

	/**
	 * 권한 조회 Mapper를 주입받는다.
	 *
	 * @param mapper 부서 역할 Mapper
	 */
	public DatabaseDepartmentAuthorization(DepartmentAuthorizationMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void requireDepartmentAdmin(AccountActor actor, long departmentId) {
		if (departmentId <= 0
				|| mapper.countActiveDepartmentAdmin(actor.accountId(), departmentId) != 1) {
			throw new DepartmentAccessDeniedException();
		}
	}
}
