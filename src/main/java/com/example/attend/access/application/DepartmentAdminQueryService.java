package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.access.infrastructure.mybatis.DepartmentAdminQueryMapper;
import com.example.attend.common.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 정확한 부서 권한을 확인한 뒤 부서 관리자 화면 읽기 모델을 반환한다.
 */
@Service
public class DepartmentAdminQueryService {

	private final DepartmentAuthorization authorization;
	private final DepartmentAdminQueryMapper mapper;
	private final Clock clock;

	/** 부서 인가, 읽기 Mapper와 업무 시계를 주입받는다. */
	public DepartmentAdminQueryService(
			DepartmentAuthorization authorization,
			DepartmentAdminQueryMapper mapper,
			Clock clock) {
		this.authorization = authorization;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** 권한이 있는 활성 부서의 기본 정보를 조회한다. */
	@Transactional(readOnly = true)
	public Map<String, Object> department(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		Map<String, Object> result = mapper.selectDepartment(departmentId);
		if (result == null) {
			throw new ResourceNotFoundException("department");
		}
		return result;
	}

	/** 서버 기준 오늘의 출석 집계를 조회한다. */
	@Transactional(readOnly = true)
	public Map<String, Object> dashboard(AccountActor actor, long departmentId) {
		department(actor, departmentId);
		return mapper.selectDashboard(departmentId, LocalDate.now(clock));
	}

	/** 현재 활성 소속 교사와 마스킹된 카드 정보를 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> teachers(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectTeachers(departmentId);
	}

	/** 부서의 정책 버전 목록을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> policies(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectPolicies(departmentId);
	}

	/**
	 * 한 정책 버전을 부서 범위로 조회한다.
	 */
	/** 한 정책의 순서화된 출석 구간을 조회한다. */
	@Transactional(readOnly = true)
	public Map<String, Object> policy(
			AccountActor actor,
			long departmentId,
			long policyId) {
		authorize(actor, departmentId);
		Map<String, Object> result = mapper.selectPolicy(departmentId, policyId);
		if (result == null) {
			throw new ResourceNotFoundException("attendance policy");
		}
		return result;
	}

	/** 날짜 생성에 사용할 발행 정책만 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> policyBands(
			AccountActor actor, long departmentId, long policyId) {
		authorize(actor, departmentId);
		return mapper.selectPolicyBands(departmentId, policyId);
	}

	/** 부서의 출석 날짜 목록을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> publishedPolicies(
			AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectPublishedPolicies(departmentId);
	}

	/** 한 출석 날짜와 고정 정책 요약을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> attendanceDays(
			AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectAttendanceDays(departmentId);
	}

	/** 한 날짜의 대상자와 결과 행을 함께 조회한다. */
	@Transactional(readOnly = true)
	public Map<String, Object> attendanceDay(
			AccountActor actor, long departmentId, long attendanceDayId) {
		authorize(actor, departmentId);
		Map<String, Object> result = mapper.selectAttendanceDay(
				departmentId, attendanceDayId);
		if (result == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		return result;
	}

	/** 부서 ID로 제한한 감사 이력을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> attendanceRows(
			AccountActor actor, long departmentId, long attendanceDayId) {
		attendanceDay(actor, departmentId, attendanceDayId);
		return mapper.selectAttendanceRows(departmentId, attendanceDayId);
	}

	/** 부서 ID로 제한한 마스킹 태깅 이력을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> history(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectHistory(departmentId);
	}

	/** 미등록·비활성 카드 이벤트의 최근 목록을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> tagHistory(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectTagHistory(departmentId);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> cardInbox(AccountActor actor, long departmentId) {
		authorize(actor, departmentId);
		return mapper.selectCardInbox(departmentId);
	}

	private void authorize(AccountActor actor, long departmentId) {
		authorization.requireDepartmentAdmin(actor, departmentId);
	}
}
