package com.example.attend.access.application;

import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.common.error.AdminWritesDisabledException;
import com.example.attend.config.AdminSecurityProperties;
import org.springframework.stereotype.Component;

/**
 * 운영자가 관리자 쓰기를 일괄 중지할 수 있는 feature gate다.
 */
@Component
public final class AdminWriteGate implements AdminWriteAuthorization {

	private final AdminSecurityProperties properties;

	/**
	 * 외부 관리자 설정을 주입받는다.
	 *
	 * @param properties 관리자 보안 설정
	 */
	public AdminWriteGate(AdminSecurityProperties properties) {
		this.properties = properties;
	}

	/**
	 * 쓰기 기능이 꺼져 있으면 업무 명령을 중단한다.
	 */
	@Override
	public void requireEnabled() {
		if (!properties.writeEnabled()) {
			throw new AdminWritesDisabledException();
		}
	}

	/**
	 * 화면의 읽기 전용 배너에 사용할 현재 상태다.
	 *
	 * @return 쓰기가 허용되면 {@code true}
	 */
	@Override
	public boolean isEnabled() {
		return properties.writeEnabled();
	}
}
