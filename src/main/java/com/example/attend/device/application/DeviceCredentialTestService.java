package com.example.attend.device.application;

import com.example.attend.device.infrastructure.mybatis.DeviceApiMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceRuntimeRow;
import com.example.attend.device.security.DevicePrincipal;
import com.example.attend.device.web.DeviceResponseBody;
import com.example.attend.device.web.DeviceResponseWriter;
import com.example.attend.organization.api.DepartmentLock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 INACTIVE 장치가 현재 자격증명을 시험한 증거를 원자적으로 기록한다.
 */
@Service
public class DeviceCredentialTestService {

	private final DepartmentLock departmentLock;
	private final DeviceApiMapper mapper;
	private final DeviceResponseWriter responseWriter;

	/** 잠금 경계, 장치 저장소와 공통 응답 생성기를 주입받는다. */
	public DeviceCredentialTestService(
			DepartmentLock departmentLock,
			DeviceApiMapper mapper,
			DeviceResponseWriter responseWriter) {
		this.departmentLock = departmentLock;
		this.mapper = mapper;
		this.responseWriter = responseWriter;
	}

	/**
	 * 인증 뒤 상태·세대를 다시 잠가 확인하고 현재 세대의 시험 시각만 갱신한다.
	 */
	@Transactional
	public DeviceHttpResult test(
			DevicePrincipal principal,
			Instant receivedAt) {
		departmentLock.lockActive(principal.departmentId());
		DeviceRuntimeRow device = mapper.lockRuntimeDevice(
				principal.deviceId(), principal.departmentId());
		if (device == null
				|| !"INACTIVE".equals(device.status())
				|| device.credentialVersion() != principal.credentialVersion()
				|| mapper.markCredentialTested(
						principal.deviceId(),
						principal.credentialVersion(),
						receivedAt) != 1) {
			return response(
					409,
					false,
					"CREDENTIAL_TEST_NOT_ALLOWED",
					"현재 장치 상태에서는 인증 시험을 할 수 없습니다.",
					receivedAt,
					null);
		}
		return response(
				200,
				true,
				"CREDENTIAL_VALID",
				"장치 인증정보가 유효합니다.",
				receivedAt,
				new CredentialTestData(
						device.status(), device.credentialVersion()));
	}

	private DeviceHttpResult response(
			int status,
			boolean success,
			String code,
			String message,
			Instant serverTime,
			Object data) {
		DeviceResponseBody body = responseWriter.body(
				success, code, message, null, serverTime, data);
		return new DeviceHttpResult(status, responseWriter.serialize(body));
	}
}
