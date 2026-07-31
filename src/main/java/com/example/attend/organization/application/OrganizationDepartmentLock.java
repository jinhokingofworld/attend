package com.example.attend.organization.application;

import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import com.example.attend.organization.infrastructure.mybatis.OrganizationMapper;
import org.springframework.stereotype.Component;

/**
 * 조직 Mapper를 통해 활성 부서 행 잠금을 제공한다.
 */
@Component
public final class OrganizationDepartmentLock implements DepartmentLock {

	private final OrganizationMapper mapper;

	/**
	 * 조직 Mapper를 주입받는다.
	 *
	 * @param mapper 조직 Mapper
	 */
	public OrganizationDepartmentLock(OrganizationMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void lockActive(long departmentId) {
		if (mapper.lockActiveDepartment(departmentId) == null) {
			throw new ResourceNotFoundException("active department");
		}
	}
}
