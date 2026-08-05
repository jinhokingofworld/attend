/**
 * 데이터베이스 구조를 안전하게 도입하고 애플리케이션과의 호환성을 검증한다.
 *
 * <p>이 패키지는 출석 업무를 처리하는 도메인 패키지가 아니다. 운영 DB를
 * 변경하기 전의 읽기 전용 사전검사, 별도 migration 프로세스, Flyway 실행,
 * 운영 애플리케이션 시작 시 버전 확인처럼 “DB 변경 경계”에 해당하는 코드만
 * 포함한다.</p>
 *
 * <p>전체 흐름은 다음과 같다.</p>
 *
 * <pre>
 * 운영 승인
 *   -&gt; {@link com.example.attend.database.DatabasePreflightInspector}
 *   -&gt; {@link com.example.attend.database.DatabaseMigrationRunner}
 *   -&gt; Flyway V001~V011
 *   -&gt; {@link com.example.attend.database.SchemaVersionGuard}
 * </pre>
 */
package com.example.attend.database;
