package com.example.attend.access.security;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.domain.AccountSystemRole;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 인증된 계정을 Spring Security가 이해할 수 있는 형태로 표현한다.
 *
 * <p>업무 서비스에는 사용자명 대신 변경되지 않는 {@link #accountId}를 전달한다.
 * 부서별 최종 권한 판단은 세션에 저장된 authority가 아니라 DB를 다시 조회하는
 * {@code DepartmentAuthorization}이 담당한다.</p>
 */
public final class AccountPrincipal implements UserDetails, CredentialsContainer {

	private final long accountId;
	private final String username;
	private String passwordHash;
	private final AccountSystemRole systemRole;
	private final List<GrantedAuthority> authorities;

	/**
	 * 계정과 현재 역할로 principal을 만든다.
	 *
	 * @param accountId 계정 식별자
	 * @param username 정규 사용자명
	 * @param passwordHash BCrypt 비밀번호 해시
	 * @param systemRole 시스템 역할, 없으면 {@code null}
	 * @param departmentAdmin 활성 부서 관리자 역할 보유 여부
	 */
	public AccountPrincipal(
			long accountId,
			String username,
			String passwordHash,
			AccountSystemRole systemRole,
			boolean departmentAdmin) {
		this.accountId = accountId;
		this.username = username;
		this.passwordHash = passwordHash;
		this.systemRole = systemRole;
		this.authorities = buildAuthorities(systemRole, departmentAdmin);
	}

	private static List<GrantedAuthority> buildAuthorities(
			AccountSystemRole systemRole,
			boolean departmentAdmin) {
		List<GrantedAuthority> values = new ArrayList<>();
		if (systemRole == AccountSystemRole.SYSTEM_ADMIN) {
			values.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
		}
		if (departmentAdmin) {
			values.add(new SimpleGrantedAuthority("ROLE_DEPARTMENT_ADMIN"));
		}
		return Collections.unmodifiableList(values);
	}

	/**
	 * 업무 서비스가 사용하는 최소 행위자 객체로 변환한다.
	 *
	 * @return 현재 계정 식별자를 가진 행위자
	 */
	public AccountActor toActor() {
		return new AccountActor(accountId);
	}

	/**
	 * 인증 계정의 불변 식별자를 반환한다.
	 *
	 * @return 계정 식별자
	 */
	public long accountId() {
		return accountId;
	}

	/**
	 * 시스템 역할을 반환한다.
	 *
	 * @return 시스템 역할, 없으면 {@code null}
	 */
	public AccountSystemRole systemRole() {
		return systemRole;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getPassword() {
		return passwordHash;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getUsername() {
		return username;
	}

	/**
	 * DB 조회가 활성 계정만 반환하므로 항상 {@code true}다.
	 *
	 * @return {@code true}
	 */
	@Override
	public boolean isEnabled() {
		return true;
	}

	/**
	 * MVP에는 별도 계정 만료 정책이 없다.
	 *
	 * @return {@code true}
	 */
	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	/**
	 * 계정 잠금 대신 {@code DISABLED} 상태를 사용한다.
	 *
	 * @return {@code true}
	 */
	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	/**
	 * MVP에는 별도 비밀번호 만료 주기가 없다.
	 *
	 * @return {@code true}
	 */
	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	/**
	 * 인증 완료 뒤 세션에 비밀번호 해시가 남지 않도록 제거한다.
	 */
	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}

	/**
	 * 로그에 사용자명이나 비밀번호 해시를 노출하지 않는다.
	 *
	 * @return 계정 식별자만 포함한 문자열
	 */
	@Override
	public String toString() {
		return "AccountPrincipal[accountId=%d]".formatted(accountId);
	}
}
