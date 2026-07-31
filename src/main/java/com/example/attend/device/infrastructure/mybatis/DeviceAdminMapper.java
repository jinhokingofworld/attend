package com.example.attend.device.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 시스템 관리자의 장치 수명주기 전용 MyBatis Mapper다.
 */
@Mapper
public interface DeviceAdminMapper {

	/** 장치 목록을 비밀키 hash 없이 조회한다. */
	List<DeviceAdminRow> selectDevices();

	/** 장치 상세를 비밀키 hash 없이 조회한다. */
	DeviceAdminRow selectDevice(@Param("deviceId") long deviceId);

	/** 장치 잠금보다 먼저 고정 부서를 확인한다. */
	Long selectDepartmentId(@Param("deviceId") long deviceId);

	/** 부서 잠금 이후 장치 상태를 행 잠금으로 다시 읽는다. */
	DeviceAdminRow lockDevice(
			@Param("deviceId") long deviceId,
			@Param("departmentId") long departmentId);

	/** 새 장치를 항상 INACTIVE 상태로 생성하고 식별자를 반환한다. */
	long insertDevice(
			@Param("departmentId") long departmentId,
			@Param("deviceCode") String deviceCode,
			@Param("name") String name,
			@Param("credentialHash") String credentialHash,
			@Param("issuedAt") Instant issuedAt);

	/** 시험 증거가 있는 INACTIVE 장치를 활성화한다. */
	int activate(@Param("deviceId") long deviceId);

	/** ACTIVE 장치를 비활성화하고 시험 증거를 제거한다. */
	int deactivate(@Param("deviceId") long deviceId);

	/** 새 hash 저장과 세대 증가, 시험 증거 제거를 원자적으로 처리한다. */
	int rotateCredential(
			@Param("deviceId") long deviceId,
			@Param("credentialHash") String credentialHash,
			@Param("issuedAt") Instant issuedAt);

	/** 장치를 재사용할 수 없는 REVOKED 종결 상태로 바꾼다. */
	int revoke(@Param("deviceId") long deviceId);
}
