package com.example.attend.organization.domain;

/**
 * 활성 카드 연결을 종료할 때 카드에 적용할 상태다.
 */
public enum CardDisposition {
	/** 회수한 정상 카드를 다시 연결할 수 있게 한다. */
	AVAILABLE,
	/** 회수하지 못한 카드를 분실 상태로 둔다. */
	LOST,
	/** 손상·오입력 카드를 영구 폐기한다. */
	RETIRED;

	/**
	 * 종료 처리를 DB 카드 상태로 변환한다.
	 *
	 * @return 종료 후 카드 상태
	 */
	public CardStatus targetStatus() {
		return CardStatus.valueOf(name());
	}
}
