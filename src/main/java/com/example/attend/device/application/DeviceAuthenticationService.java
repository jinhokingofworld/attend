package com.example.attend.device.application;

import com.example.attend.device.infrastructure.mybatis.DeviceApiMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceCredentialRow;
import com.example.attend.device.security.DevicePrincipal;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장치 code/key를 검증하고 마지막 인증 성공 telemetry를 짧은 독립 트랜잭션에 남긴다.
 */
@Service
public class DeviceAuthenticationService {

	private static final String DUMMY_HASH = "0".repeat(64);
	private final DeviceApiMapper mapper;
	private final DeviceCredentialHasher credentialHasher;
	private final Clock clock;

	/**
	 * 자격증명 저장소, HMAC 비교기와 서버 시계를 주입받는다.
	 */
	public DeviceAuthenticationService(
			DeviceApiMapper mapper,
			DeviceCredentialHasher credentialHasher,
			Clock clock) {
		this.mapper = mapper;
		this.credentialHasher = credentialHasher;
		this.clock = clock;
	}

	/**
	 * 성공한 인증만 principal을 만들고 후속 업무 rollback과 무관하게 telemetry를 남긴다.
	 *
	 * @param deviceCode 공개 장치 코드
	 * @param deviceKey 원문 장치 키
	 * @return 인증 주체, 실패하면 {@code null}
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public DevicePrincipal authenticate(String deviceCode, String deviceKey) {
		DeviceCredentialRow row = mapper.selectCredentialByCode(deviceCode);
		boolean matched = credentialHasher.matches(
				deviceKey, row == null ? DUMMY_HASH : row.credentialHash());
		if (row == null || !matched) {
			return null;
		}
		if (mapper.updateLastSeen(
				row.id(), row.credentialVersion(), clock.instant()) != 1) {
			return null;
		}
		return new DevicePrincipal(
				row.id(), row.departmentId(), row.credentialVersion());
	}
}
