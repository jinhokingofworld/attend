package com.example.attend.organization.infrastructure.mybatis;

/**
 * 잠긴 NFC 카드 행이다.
 *
 * @param id 카드 식별자
 * @param uid 정규화 UID
 * @param status 현재 상태 문자열
 */
public record CardRow(long id, String uid, String status) {
}
