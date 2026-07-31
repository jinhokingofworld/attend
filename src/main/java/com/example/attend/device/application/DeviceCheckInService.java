package com.example.attend.device.application;

import com.example.attend.attendance.domain.AttendanceBand;
import com.example.attend.attendance.domain.AttendanceDecision;
import com.example.attend.attendance.domain.AttendancePolicy;
import com.example.attend.attendance.domain.AttendancePolicyEvaluator;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceRecordMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceRecordRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceTargetRow;
import com.example.attend.device.infrastructure.mybatis.DeviceApiMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceAttendanceDayRow;
import com.example.attend.device.infrastructure.mybatis.DeviceCardCandidateRow;
import com.example.attend.device.infrastructure.mybatis.DeviceCheckInMapper;
import com.example.attend.device.infrastructure.mybatis.DeviceEligibilityRow;
import com.example.attend.device.infrastructure.mybatis.DeviceRuntimeRow;
import com.example.attend.device.infrastructure.mybatis.TagEventRow;
import com.example.attend.device.security.DevicePrincipal;
import com.example.attend.device.web.CheckInRequest;
import com.example.attend.device.web.DeviceResponseBody;
import com.example.attend.device.web.DeviceResponseWriter;
import com.example.attend.organization.api.DepartmentLock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증된 장치의 멱등 event와 NFC 출석 판정을 한 업무 트랜잭션으로 확정한다.
 */
@Service
public class DeviceCheckInService {

	private final DepartmentLock departmentLock;
	private final DeviceApiMapper deviceMapper;
	private final DeviceCheckInMapper checkInMapper;
	private final AttendancePolicyMapper policyMapper;
	private final AttendanceRecordMapper recordMapper;
	private final DeviceResponseWriter responseWriter;
	private final ZoneId attendanceZone;
	private final AttendancePolicyEvaluator evaluator =
			new AttendancePolicyEvaluator();

	/** check-in 잠금·정책·기록·응답 협력 객체를 주입받는다. */
	public DeviceCheckInService(
			DepartmentLock departmentLock,
			DeviceApiMapper deviceMapper,
			DeviceCheckInMapper checkInMapper,
			AttendancePolicyMapper policyMapper,
			AttendanceRecordMapper recordMapper,
			DeviceResponseWriter responseWriter,
			ZoneId attendanceZone) {
		this.departmentLock = departmentLock;
		this.deviceMapper = deviceMapper;
		this.checkInMapper = checkInMapper;
		this.policyMapper = policyMapper;
		this.recordMapper = recordMapper;
		this.responseWriter = responseWriter;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 같은 requestId 재시도는 최초 DB 응답을 재현하고 새 요청만 출석을 판정한다.
	 */
	@Transactional
	public DeviceHttpResult checkIn(
			DevicePrincipal principal,
			CheckInRequest request,
			Instant receivedAt) {
		departmentLock.lockActive(principal.departmentId());
		DeviceRuntimeRow device = deviceMapper.lockRuntimeDevice(
				principal.deviceId(), principal.departmentId());
		if (device == null) {
			throw new DeviceStateChangedException(request.requestId());
		}

		if (checkInMapper.claimEvent(
				principal.deviceId(),
				principal.departmentId(),
				request.requestId(),
				request.uid(),
				receivedAt) == 0) {
			return replayOrConflict(principal, request, receivedAt);
		}
		if (!"ACTIVE".equals(device.status())
				|| device.credentialVersion() != principal.credentialVersion()) {
			throw new DeviceStateChangedException(request.requestId());
		}

		DeviceCardCandidateRow candidate =
				checkInMapper.selectCardCandidate(request.uid());
		if (candidate == null) {
			return complete(
					principal, request, receivedAt, 404, false, "UNKNOWN_UID",
					"등록되지 않은 카드입니다.", null, null, null, null);
		}
		if (!"ACTIVE".equals(candidate.cardStatus())) {
			return complete(
					principal, request, receivedAt, 409, false, "INACTIVE_CARD",
					"사용할 수 없는 카드입니다.", candidate.cardId(),
					null, null, null);
		}
		if (candidate.assignmentDepartmentId() == null
				|| candidate.assignmentDepartmentId() != principal.departmentId()) {
			return complete(
					principal, request, receivedAt, 409, false,
					"NOT_DEPARTMENT_MEMBER",
					"이 부서에 유효한 소속이 없습니다.", candidate.cardId(),
					null, null, null);
		}

		LocalDate attendanceDate =
				LocalDate.ofInstant(receivedAt, attendanceZone);
		DeviceAttendanceDayRow day = checkInMapper.lockAttendanceDay(
				principal.departmentId(), attendanceDate);
		if (day == null) {
			return complete(
					principal, request, receivedAt, 409, false,
					"NO_ATTENDANCE_DAY",
					"오늘 등록된 출석 대상 날짜가 없습니다.", candidate.cardId(),
					null, null, null);
		}
		if (!"SCHEDULED".equals(day.status())) {
			return complete(
					principal, request, receivedAt, 409, false,
					"CHECK_IN_CLOSED",
					"출석 가능 시간이 종료되었습니다.", candidate.cardId(),
					day.id(), null, null);
		}

		DeviceEligibilityRow eligibility = checkInMapper.lockEligibility(
				principal.departmentId(), request.uid());
		if (eligibility == null) {
			return complete(
					principal, request, receivedAt, 409, false,
					"NOT_DEPARTMENT_MEMBER",
					"이 부서에 유효한 소속이 없습니다.", candidate.cardId(),
					day.id(), null, null);
		}
		AttendanceTargetRow target = recordMapper.lockTarget(
				principal.departmentId(), day.id(), eligibility.memberId());
		if (target == null || !target.isTarget()) {
			return complete(
					principal, request, receivedAt, 409, false,
					"NOT_ATTENDANCE_TARGET",
					"오늘 출석 대상자로 등록되어 있지 않습니다.",
					eligibility.cardId(), day.id(), null, null);
		}

		List<AttendanceBand> bands = policyMapper.selectBands(
				day.policyVersionId());
		AttendanceDecision decision = evaluator.evaluate(
				new AttendancePolicy(
						day.policyVersionId(),
						day.checkInStartTime(),
						bands),
				receivedAt,
				attendanceZone);
		if (decision instanceof AttendanceDecision.CheckInNotOpen) {
			return complete(
					principal, request, receivedAt, 409, false,
					"CHECK_IN_NOT_OPEN",
					"아직 출석 가능 시간이 아닙니다.",
					eligibility.cardId(), day.id(), null, null);
		}
		if (decision instanceof AttendanceDecision.CheckInClosed) {
			return complete(
					principal, request, receivedAt, 409, false,
					"CHECK_IN_CLOSED",
					"출석 가능 시간이 종료되었습니다.",
					eligibility.cardId(), day.id(), null, null);
		}

		AttendanceRecordRow existing = recordMapper.lockRecord(
				day.id(), eligibility.memberId());
		if (existing != null) {
			return complete(
					principal, request, receivedAt, 200, true,
					"ALREADY_CHECKED_IN",
					"이미 출석이 처리되었습니다.",
					eligibility.cardId(), day.id(), existing.id(), null);
		}

		AttendanceBand band = ((AttendanceDecision.Matched) decision).band();
		String status = band.parentStatus().name();
		long recordId = checkInMapper.insertAttendanceRecord(
				day.id(),
				day.policyVersionId(),
				eligibility.memberId(),
				band.id(),
				status,
				band.sequenceNo(),
				band.label(),
				receivedAt);
		String code = "PRESENT".equals(status) ? "CHECKED_IN" : "LATE";
		String message = "PRESENT".equals(status)
				? "정상 출석이 기록되었습니다."
				: "지각이 기록되었습니다.";
		CheckInData data = new CheckInData(
				status,
				new CheckInBandData(band.sequenceNo(), band.label()),
				OffsetDateTime.ofInstant(receivedAt, attendanceZone));
		return complete(
				principal, request, receivedAt, 201, true, code, message,
				eligibility.cardId(), day.id(), recordId, data);
	}

	private DeviceHttpResult replayOrConflict(
			DevicePrincipal principal,
			CheckInRequest request,
			Instant receivedAt) {
		TagEventRow event = checkInMapper.selectEvent(
				principal.deviceId(), request.requestId());
		if (event == null || event.httpStatus() == null || event.responseBody() == null) {
			throw new IllegalStateException("claimed tag event has no terminal response");
		}
		if (!event.uid().equals(request.uid())) {
			return immediate(
					409, false, "REQUEST_ID_CONFLICT",
					"요청 식별자가 이미 다른 태깅에 사용되었습니다.",
					request.requestId(), receivedAt, null);
		}
		return new DeviceHttpResult(event.httpStatus(), event.responseBody());
	}

	private DeviceHttpResult complete(
			DevicePrincipal principal,
			CheckInRequest request,
			Instant receivedAt,
			int status,
			boolean success,
			String code,
			String message,
			Long cardId,
			Long dayId,
			Long recordId,
			Object data) {
		DeviceResponseBody body = responseWriter.body(
				success, code, message, request.requestId(), receivedAt, data);
		String canonical = checkInMapper.completeEvent(
				principal.deviceId(),
				request.requestId(),
				cardId,
				dayId,
				recordId,
				code,
				status,
				responseWriter.serialize(body));
		if (canonical == null) {
			throw new IllegalStateException("tag event could not be completed");
		}
		return new DeviceHttpResult(status, canonical);
	}

	private DeviceHttpResult immediate(
			int status,
			boolean success,
			String code,
			String message,
			String requestId,
			Instant serverTime,
			Object data) {
		return new DeviceHttpResult(
				status,
				responseWriter.serialize(responseWriter.body(
						success,
						code,
						message,
						requestId,
						serverTime,
						data)));
	}
}
