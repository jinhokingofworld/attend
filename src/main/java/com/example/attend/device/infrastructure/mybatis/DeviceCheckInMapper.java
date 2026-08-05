package com.example.attend.device.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * NFC check-in의 event 선점, 자격 잠금과 출석 저장 SQL 경계다.
 */
@Mapper
public interface DeviceCheckInMapper {

	/** 새 `(device, requestId)` 처리권을 PROCESSING 행으로 선점한다. */
	int claimEvent(
			@Param("deviceId") long deviceId,
			@Param("departmentId") long departmentId,
			@Param("requestId") String requestId,
			@Param("uid") String uid);

	/** 선점에 실패한 동일 요청의 최초 확정 응답을 읽는다. */
	TagEventRow selectEvent(
			@Param("deviceId") long deviceId,
			@Param("requestId") String requestId);

	/** 카드 존재·상태와 활성 연결 부서를 예비 조회한다. */
	DeviceCardCandidateRow selectCardCandidate(@Param("uid") String uid);

	/** 서버 업무 날짜의 출석일을 고정 정책과 함께 잠근다. */
	DeviceAttendanceDayRow lockAttendanceDay(
			@Param("departmentId") long departmentId,
			@Param("attendanceDate") LocalDate attendanceDate);

	/** 날짜 잠금 뒤 카드·자기 부서 연결·활성 소속을 함께 다시 잠근다. */
	DeviceEligibilityRow lockEligibility(
			@Param("departmentId") long departmentId,
			@Param("uid") String uid);

	/** NFC 출석 기록을 생성하고 식별자를 반환한다. */
	long insertAttendanceRecord(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("policyVersionId") long policyVersionId,
			@Param("memberId") long memberId,
			@Param("attendanceBandId") long attendanceBandId,
			@Param("status") String status,
			@Param("bandSequence") int bandSequence,
			@Param("bandLabel") String bandLabel,
			@Param("checkedInAt") Instant checkedInAt);

	/**
	 * PROCESSING event를 확정하고 DB가 정규화한 canonical JSON을 반환한다.
	 */
	String completeEvent(
			@Param("deviceId") long deviceId,
			@Param("requestId") String requestId,
			@Param("nfcCardId") Long nfcCardId,
			@Param("attendanceDayId") Long attendanceDayId,
			@Param("attendanceRecordId") Long attendanceRecordId,
			@Param("resultCode") String resultCode,
			@Param("httpStatus") int httpStatus,
			@Param("responseBody") String responseBody);
}
