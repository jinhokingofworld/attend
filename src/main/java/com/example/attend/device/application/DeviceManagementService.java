package com.example.attend.device.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.device.domain.DeviceStatus;
import com.example.attend.device.infrastructure.mybatis.DeviceAdminMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceAdminRow;
import com.example.attend.organization.api.DepartmentLock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시스템 관리자의 장치 생성·활성화·비활성화·키 교체·폐기를 처리한다.
 */
@Service
public class DeviceManagementService {

	private final SystemAuthorization authorization;
	private final AdminWriteGate writeGate;
	private final DepartmentLock departmentLock;
	private final DeviceAdminMapper mapper;
	private final DeviceCredentialHasher credentialHasher;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 장치 관리에 필요한 권한, 잠금, 저장소와 암호 구성요소를 주입받는다.
	 */
	public DeviceManagementService(
			SystemAuthorization authorization,
			AdminWriteGate writeGate,
			DepartmentLock departmentLock,
			DeviceAdminMapper mapper,
			DeviceCredentialHasher credentialHasher,
			AuditLogWriter auditLogWriter,
			Clock clock) {
		this.authorization = authorization;
		this.writeGate = writeGate;
		this.departmentLock = departmentLock;
		this.mapper = mapper;
		this.credentialHasher = credentialHasher;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
	}

	/** 비밀키 hash를 제외한 모든 장치 목록을 조회한다. */
	@Transactional(readOnly = true)
	public List<DeviceAdminRow> devices(AccountActor actor) {
		authorization.requireSystemAdmin(actor);
		return mapper.selectDevices();
	}

	/** 비밀키 hash를 제외한 장치 상세를 조회한다. */
	@Transactional(readOnly = true)
	public DeviceAdminRow device(AccountActor actor, long deviceId) {
		authorization.requireSystemAdmin(actor);
		return requireDevice(deviceId);
	}

	/**
	 * 고정 부서에 INACTIVE 장치를 만들고 원문 키를 호출자에게 한 번만 반환한다.
	 */
	@Transactional
	public IssuedDeviceCredential create(
			AccountActor actor,
			long departmentId,
			String deviceCode,
			String name) {
		requireWriteAndSystemAdmin(actor);
		deviceCode = normalizeDeviceCode(deviceCode);
		name = normalizeRequired(name, "device name", 100);
		departmentLock.lockActive(departmentId);
		String key = credentialHasher.generateKey();
		Instant issuedAt = clock.instant();
		try {
			long deviceId = mapper.insertDevice(
					departmentId,
					deviceCode,
					name,
					credentialHasher.hash(key),
					issuedAt);
			auditLogWriter.writeAccount(
					departmentId, actor, null, "DEVICE_CREATED", "DEVICE",
					Long.toString(deviceId), null,
					Map.of(
							"deviceCode", deviceCode,
							"name", name,
							"status", DeviceStatus.INACTIVE.name(),
							"credentialVersion", 1),
					null);
			return new IssuedDeviceCredential(
					deviceId, deviceCode, key, 1, issuedAt);
		} catch (DuplicateKeyException exception) {
			throw new BusinessRuleException("device code already exists");
		}
	}

	/** 현재 키 시험 증거가 있는 INACTIVE 장치를 ACTIVE로 전환한다. */
	@Transactional
	public void activate(AccountActor actor, long deviceId) {
		requireWriteAndSystemAdmin(actor);
		DeviceAdminRow device = lockDeviceInOrder(deviceId);
		if (!DeviceStatus.INACTIVE.name().equals(device.status())
				|| !device.currentCredentialTested()
				|| mapper.activate(deviceId) != 1) {
			throw new BusinessRuleException(
					"current credential must pass device test before activation");
		}
		writeStateAudit(actor, device, "DEVICE_ACTIVATED", DeviceStatus.ACTIVE, null);
	}

	/** ACTIVE 장치를 즉시 INACTIVE로 바꾸고 재시험을 요구한다. */
	@Transactional
	public void deactivate(AccountActor actor, long deviceId, String reason) {
		requireWriteAndSystemAdmin(actor);
		reason = normalizeReason(reason);
		DeviceAdminRow device = lockDeviceInOrder(deviceId);
		if (!DeviceStatus.ACTIVE.name().equals(device.status())
				|| mapper.deactivate(deviceId) != 1) {
			throw new BusinessRuleException("only an active device can be deactivated");
		}
		writeStateAudit(actor, device, "DEVICE_DEACTIVATED", DeviceStatus.INACTIVE, reason);
	}

	/**
	 * 신·구 키 중첩 없이 새 키로 즉시 교체하고 INACTIVE 상태로 되돌린다.
	 */
	@Transactional
	public IssuedDeviceCredential rotateCredential(
			AccountActor actor,
			long deviceId,
			String deviceCodeConfirmation,
			String reason) {
		requireWriteAndSystemAdmin(actor);
		reason = normalizeReason(reason);
		DeviceAdminRow device = lockDeviceInOrder(deviceId);
		requireCodeConfirmation(device, deviceCodeConfirmation);
		if (DeviceStatus.REVOKED.name().equals(device.status())) {
			throw new BusinessRuleException("revoked device credential cannot be rotated");
		}
		String key = credentialHasher.generateKey();
		Instant issuedAt = clock.instant();
		if (mapper.rotateCredential(
				deviceId, credentialHasher.hash(key), issuedAt) != 1) {
			throw new BusinessRuleException("device credential changed concurrently");
		}
		int nextVersion = device.credentialVersion() + 1;
		auditLogWriter.writeAccount(
				device.departmentId(), actor, null,
				"DEVICE_CREDENTIAL_ROTATED", "DEVICE", Long.toString(deviceId),
				Map.of(
						"status", device.status(),
						"credentialVersion", device.credentialVersion()),
				Map.of(
						"status", DeviceStatus.INACTIVE.name(),
						"credentialVersion", nextVersion),
				reason);
		return new IssuedDeviceCredential(
				deviceId, device.deviceCode(), key, nextVersion, issuedAt);
	}

	/** 장치를 다시 사용할 수 없는 REVOKED 상태로 끝낸다. */
	@Transactional
	public void revoke(
			AccountActor actor,
			long deviceId,
			String deviceCodeConfirmation,
			String reason) {
		requireWriteAndSystemAdmin(actor);
		reason = normalizeReason(reason);
		DeviceAdminRow device = lockDeviceInOrder(deviceId);
		requireCodeConfirmation(device, deviceCodeConfirmation);
		if (DeviceStatus.REVOKED.name().equals(device.status())
				|| mapper.revoke(deviceId) != 1) {
			throw new BusinessRuleException("device is already revoked");
		}
		writeStateAudit(actor, device, "DEVICE_REVOKED", DeviceStatus.REVOKED, reason);
	}

	private DeviceAdminRow requireDevice(long deviceId) {
		DeviceAdminRow device = mapper.selectDevice(deviceId);
		if (device == null) {
			throw new ResourceNotFoundException("device");
		}
		return device;
	}

	private DeviceAdminRow lockDeviceInOrder(long deviceId) {
		Long departmentId = mapper.selectDepartmentId(deviceId);
		if (departmentId == null) {
			throw new ResourceNotFoundException("device");
		}
		departmentLock.lockActive(departmentId);
		DeviceAdminRow device = mapper.lockDevice(deviceId, departmentId);
		if (device == null) {
			throw new ResourceNotFoundException("device");
		}
		return device;
	}

	private void requireWriteAndSystemAdmin(AccountActor actor) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
	}

	private static void requireCodeConfirmation(
			DeviceAdminRow device,
			String confirmation) {
		if (confirmation == null
				|| !device.deviceCode().equals(confirmation.trim())) {
			throw new BusinessRuleException("device code confirmation does not match");
		}
	}

	private void writeStateAudit(
			AccountActor actor,
			DeviceAdminRow device,
			String action,
			DeviceStatus nextStatus,
			String reason) {
		auditLogWriter.writeAccount(
				device.departmentId(), actor, null, action, "DEVICE",
				Long.toString(device.id()),
				Map.of(
						"status", device.status(),
						"credentialVersion", device.credentialVersion()),
				Map.of(
						"status", nextStatus.name(),
						"credentialVersion", device.credentialVersion()),
				reason);
	}

	private static String normalizeRequired(
			String value,
			String field,
			int maxCodePoints) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		value = value.trim();
		if (value.codePointCount(0, value.length()) > maxCodePoints) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return value;
	}

	private static String normalizeReason(String reason) {
		return normalizeRequired(reason, "reason", 500);
	}

	private static String normalizeDeviceCode(String deviceCode) {
		deviceCode = normalizeRequired(deviceCode, "device code", 100);
		if (deviceCode.indexOf('\r') >= 0 || deviceCode.indexOf('\n') >= 0) {
			throw new IllegalArgumentException(
					"device code must be valid in a single HTTP header");
		}
		return deviceCode;
	}
}
