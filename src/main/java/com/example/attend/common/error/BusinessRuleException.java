package com.example.attend.common.error;

/**
 * 입력 형식은 유효하지만 현재 업무 규칙상 실행할 수 없는 명령을 나타낸다.
 *
 * <p>웹 계층은 이 예외의 내부 메시지를 그대로 노출하지 않고, 유스케이스별 안전한
 * 안내 문구와 함께 검증 실패 또는 충돌 응답으로 변환한다.</p>
 */
public class BusinessRuleException extends RuntimeException {

	/**
	 * 업무 규칙 위반 이유를 보존한다.
	 *
	 * @param message 개발자와 테스트가 식별할 수 있는 위반 내용
	 */
	public BusinessRuleException(String message) {
		super(message);
	}
}
