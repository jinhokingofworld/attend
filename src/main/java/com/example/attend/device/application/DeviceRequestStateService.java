package com.example.attend.device.application;

import com.example.attend.device.infrastructure.mybatis.DeviceApiMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceRuntimeRow;
import com.example.attend.device.security.DevicePrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * body 검증 전에 endpoint별 장치 상태를 확인하고 업무 잠금에서 재검증할 근거를 만든다.
 */
@Service
public class DeviceRequestStateService {

	private final DeviceApiMapper mapper;

	/** 장치 runtime projection Mapper를 주입받는다. */
	public DeviceRequestStateService(DeviceApiMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * 인증한 세대의 장치가 현재 check-in 가능한 ACTIVE 상태인지 확인한다.
	 *
	 * <p>이 조회와 실제 업무 사이에는 경합이 가능하므로 체크인 transaction이
	 * 반드시 행 잠금 뒤 다시 검사해야 한다.</p>
	 */
	@Transactional(readOnly = true)
	public boolean checkInAllowed(DevicePrincipal principal) {
		DeviceRuntimeRow row = mapper.selectRuntimeDevice(
				principal.deviceId(), principal.departmentId());
		return row != null
				&& "ACTIVE".equals(row.status())
				&& row.credentialVersion() == principal.credentialVersion();
	}
}
