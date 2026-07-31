package com.example.attend.access.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 로그인 가능한 계정과 현재 활성 역할을 조회한다.
 */
@Mapper
public interface AccountSecurityMapper {

	/**
	 * 사용자명을 대소문자와 바깥 공백에 무관하게 조회한다.
	 *
	 * <p>{@code ACTIVE} 상태이며 비밀번호가 설정된 계정만 반환한다. 정지 계정과
	 * 회원가입 대기 계정을 조회 단계에서 제외하여 인증 대상이 되지 않게 한다.</p>
	 *
	 * @param username 로그인 화면에서 입력한 사용자명
	 * @return 로그인 가능한 계정, 없으면 {@code null}
	 */
	AccountSecurityRow selectActiveByUsername(
			@Param("username") String username);
}
