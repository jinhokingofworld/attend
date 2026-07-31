package com.example.attend.access.security;

import com.example.attend.access.domain.AccountSystemRole;
import com.example.attend.access.infrastructure.mybatis.AccountSecurityMapper;
import com.example.attend.access.infrastructure.mybatis.AccountSecurityRow;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * M3 계정 테이블을 Spring Security 폼 로그인에 연결한다.
 */
@Service
public final class AccountUserDetailsService implements UserDetailsService {

	private static final String GENERIC_AUTHENTICATION_FAILURE =
			"Invalid username or password";

	private final AccountSecurityMapper mapper;

	/**
	 * 계정 조회 Mapper를 주입받는다.
	 *
	 * @param mapper 인증 계정 Mapper
	 */
	public AccountUserDetailsService(AccountSecurityMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * 활성 계정을 조회해 principal로 변환한다.
	 *
	 * <p>존재하지 않는 계정, 회원가입 대기 계정, 정지 계정을 모두 같은 예외로
	 * 처리하여 로그인 화면에서 계정 존재 여부를 추측할 수 없게 한다.</p>
	 *
	 * @param username 로그인 입력 사용자명
	 * @return 인증에 사용할 계정 principal
	 * @throws UsernameNotFoundException 인증 가능한 계정이 없을 때
	 */
	@Override
	public UserDetails loadUserByUsername(String username)
			throws UsernameNotFoundException {
		if (username == null || username.isBlank()) {
			throw new UsernameNotFoundException(GENERIC_AUTHENTICATION_FAILURE);
		}

		AccountSecurityRow row = mapper.selectActiveByUsername(username);
		if (row == null) {
			throw new UsernameNotFoundException(GENERIC_AUTHENTICATION_FAILURE);
		}

		AccountSystemRole systemRole = row.systemRole() == null
				? null
				: AccountSystemRole.valueOf(row.systemRole());
		return new AccountPrincipal(
				row.id(),
				row.username(),
				row.passwordHash(),
				systemRole,
				row.departmentAdmin());
	}
}
