package com.example.attend.device.web;

/**
 * check-in 본문 parsing의 성공 또는 실패 결과다.
 *
 * @param request 검증 성공 요청
 * @param problem 검증 실패 내용
 */
public record ParsedCheckIn(
		CheckInRequest request,
		DeviceRequestProblem problem) {

	/** 검증 성공 결과를 만든다. */
	public static ParsedCheckIn success(CheckInRequest request) {
		return new ParsedCheckIn(request, null);
	}

	/** 검증 실패 결과를 만든다. */
	public static ParsedCheckIn failure(
			int status,
			String code,
			String message) {
		return new ParsedCheckIn(
				null, new DeviceRequestProblem(status, code, message));
	}

	/** 업무 service를 호출할 수 있는 성공 결과인지 알려준다. */
	public boolean successful() {
		return request != null;
	}
}
