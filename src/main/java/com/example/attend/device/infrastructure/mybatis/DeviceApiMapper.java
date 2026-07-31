package com.example.attend.device.infrastructure.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 장치 인증과 credential 시험에 필요한 최소 SQL 경계다.
 */
@Mapper
public interface DeviceApiMapper {

	/** 공개 장치 코드로 자격증명 행을 잠가 동시 키 교체와 직렬화한다. */
	DeviceCredentialRow lockCredentialByCode(
			@Param("deviceCode") String deviceCode);

	/** 성공한 인증의 telemetry만 현재 세대에 조건부 기록한다. */
	int updateLastSeen(
			@Param("deviceId") long deviceId,
			@Param("credentialVersion") int credentialVersion,
			@Param("seenAt") Instant seenAt);

	/** 부서 잠금 이후 장치 상태와 세대를 다시 잠가 읽는다. */
	DeviceRuntimeRow lockRuntimeDevice(
			@Param("deviceId") long deviceId,
			@Param("departmentId") long departmentId);

	/** body 검증 전에 endpoint가 허용하는 현재 상태인지 비잠금 조회한다. */
	DeviceRuntimeRow selectRuntimeDevice(
			@Param("deviceId") long deviceId,
			@Param("departmentId") long departmentId);

	/** INACTIVE인 동일 세대의 credential 시험 증거만 갱신한다. */
	int markCredentialTested(
			@Param("deviceId") long deviceId,
			@Param("credentialVersion") int credentialVersion,
			@Param("testedAt") Instant testedAt);
}
