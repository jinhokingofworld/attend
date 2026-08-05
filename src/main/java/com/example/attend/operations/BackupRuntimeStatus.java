package com.example.attend.operations;

import java.time.Instant;

/**
 * 외부 백업 작업이 제공한 비민감 메타데이터를 검증한 결과다.
 *
 * <p>백업 파일 경로, DB 접속 정보, checksum과 작업 로그는 이 객체에 포함하지
 * 않는다. 따라서 시스템 관리자 화면은 백업 artifact에 대한 별도 인프라 권한을
 * 우회하지 않는다.</p>
 *
 * @param state 현재 백업 상태 판정
 * @param observedAt 외부 작업이 상태를 관측·기록한 시각
 * @param lastSuccessAt 마지막 백업 성공 시각
 * @param storageType 파일 위치를 제외한 저장소 유형
 * @param lastRestoreTestAt 마지막 격리 복원 시험 성공 시각
 */
public record BackupRuntimeStatus(
		State state,
		Instant observedAt,
		Instant lastSuccessAt,
		StorageType storageType,
		Instant lastRestoreTestAt) {

	/** 화면에 출력할 수 있는 제한된 상태 집합이다. */
	public enum State {
		SUCCESS("성공"),
		FAILURE("실패 · 외부 백업 작업 확인 필요"),
		STALE("기한 초과 · 상태 source 갱신 필요"),
		NOT_CONFIGURED("확인 불가 · 상태 source 미구성"),
		UNAVAILABLE("확인 불가 · 상태 source 읽기 또는 검증 실패");

		private final String summary;

		State(String summary) {
			this.summary = summary;
		}

		/** 내부 예외나 원문 설정을 포함하지 않는 화면용 문구다. */
		public String summary() {
			return summary;
		}
	}

	/** 실제 저장소 이름이나 경로 대신 허용하는 저장소 유형이다. */
	public enum StorageType {
		OFF_HOST_FILESYSTEM("운영 서버 외부 파일 저장소"),
		OBJECT_STORAGE("접근 제한 객체 저장소"),
		MANAGED_DATABASE_BACKUP("관리형 DB 백업 저장소");

		private final String label;

		StorageType(String label) {
			this.label = label;
		}

		/** 운영 화면용 제한된 저장소 유형 설명이다. */
		public String label() {
			return label;
		}
	}

	/** 상태별 고정 문구만 반환한다. */
	public String summary() {
		return state.summary();
	}

	/** 저장소 상세 위치를 노출하지 않는 화면용 유형 문구다. */
	public String storageTypeLabel() {
		return storageType == null ? "확인 불가" : storageType.label();
	}

	static BackupRuntimeStatus notConfigured() {
		return empty(State.NOT_CONFIGURED);
	}

	static BackupRuntimeStatus unavailable() {
		return empty(State.UNAVAILABLE);
	}

	private static BackupRuntimeStatus empty(State state) {
		return new BackupRuntimeStatus(state, null, null, null, null);
	}
}
