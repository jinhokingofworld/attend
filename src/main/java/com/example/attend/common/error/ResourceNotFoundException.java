package com.example.attend.common.error;

/**
 * 승인된 부서 범위에서 업무 대상을 찾지 못했음을 나타낸다.
 *
 * <p>다른 부서에 같은 ID가 있는지 구분하지 않으므로 IDOR 공격에 자원 존재 여부를
 * 노출하지 않는다.</p>
 */
public class ResourceNotFoundException extends RuntimeException {

	/**
	 * 조회하지 못한 자원의 일반 이름으로 예외를 만든다.
	 *
	 * @param resourceName 외부 식별자를 포함하지 않는 자원 이름
	 */
	public ResourceNotFoundException(String resourceName) {
		super(resourceName + " was not found in the authorized scope");
	}
}
