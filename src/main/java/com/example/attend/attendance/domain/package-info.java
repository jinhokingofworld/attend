/**
 * Spring MVC와 MyBatis에 의존하지 않는 순수 출석 업무 규칙을 제공한다.
 *
 * <p>관리자 입력 검증과 NFC 태깅은 같은 정책 객체와 판정기를 사용하므로,
 * 화면·장치 경로에 따라 출석 결과가 달라지는 규칙 중복을 피할 수 있다.</p>
 */
package com.example.attend.attendance.domain;
