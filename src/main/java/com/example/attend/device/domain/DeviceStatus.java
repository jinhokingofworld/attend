package com.example.attend.device.domain;

/**
 * 장치가 사용할 수 있는 자격증명 수명주기 상태다.
 *
 * <p>{@link #REVOKED}는 분실·침해·영구 폐기를 나타내는 종결 상태이므로 다시
 * 활성화하거나 키를 교체할 수 없다.</p>
 */
public enum DeviceStatus {
	/** 자격증명 시험만 허용하고 출석 처리는 허용하지 않는다. */
	INACTIVE,
	/** 현재 자격증명으로 출석 처리를 허용한다. */
	ACTIVE,
	/** 어떤 장치 API도 사용할 수 없는 종결 상태다. */
	REVOKED
}
