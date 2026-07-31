package com.example.attend.organization.domain;

/**
 * NFC 카드 자체의 현재 사용 가능 상태다.
 */
public enum CardStatus {
	/** 다른 교사에게 연결할 수 있는 카드다. */
	AVAILABLE,
	/** 한 교사에게 활성 연결된 카드다. */
	ACTIVE,
	/** 분실되어 다시 연결할 수 없는 카드다. */
	LOST,
	/** 영구 폐기되어 다시 사용할 수 없는 카드다. */
	RETIRED
}
