package com.example.attend.device.application;

/**
 * 인증 뒤 업무 잠금 전에 장치 상태나 자격증명 세대가 바뀐 경합이다.
 *
 * <p>이 예외는 PROCESSING event까지 전체 rollback하기 위해 결과 객체 대신
 * 예외로 전달한다.</p>
 */
public final class DeviceStateChangedException extends RuntimeException {

	private final String requestId;

	/** 응답에 돌려줄 이미 검증된 request ID를 보관한다. */
	public DeviceStateChangedException(String requestId) {
		super("device state changed after authentication");
		this.requestId = requestId;
	}

	/** 검증을 통과한 요청 ID를 반환한다. */
	public String requestId() {
		return requestId;
	}
}
