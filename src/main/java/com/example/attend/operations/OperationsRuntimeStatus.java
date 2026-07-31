package com.example.attend.operations;

import java.time.Instant;

/**
 * 비밀 설정 원문을 제외하고 시스템 관리자 화면에 공개할 runtime 상태다.
 *
 * @param applicationVersion 배포 artifact 버전
 * @param startedAt 현재 process 시작 시각
 * @param adminWriteEnabled 관리자 쓰기 flag
 * @param deviceApiEnabled 장치 API flag
 * @param schedulerEnabled 자동 마감 scheduler flag
 * @param databaseStatus DB·schema의 제한된 상태 설명
 * @param backupStatus 외부 백업 상태 source 연동 결과
 */
public record OperationsRuntimeStatus(
		String applicationVersion,
		Instant startedAt,
		boolean adminWriteEnabled,
		boolean deviceApiEnabled,
		boolean schedulerEnabled,
		String databaseStatus,
		String backupStatus) {
}
