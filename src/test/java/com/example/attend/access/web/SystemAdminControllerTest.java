package com.example.attend.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.domain.AccountSystemRole;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.operations.BackupRuntimeStatus;
import com.example.attend.operations.BackupRuntimeStatus.State;
import com.example.attend.operations.OperationsRuntimeStatus;
import com.example.attend.operations.OperationsRuntimeStatusService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.ui.ExtendedModelMap;

/** 운영 DB 장애 중에도 비민감 진단 화면이 열리는 경계를 검증한다. */
class SystemAdminControllerTest {

	/** 집계 예외 원문 없이 runtime DB 장애와 확인 불가 집계를 렌더링한다. */
	@Test
	void rendersSafeRuntimeStatusWhenDatabaseAggregationFails() {
		SystemAdministrationService administrationService =
				mock(SystemAdministrationService.class);
		AdminWriteGate writeGate = mock(AdminWriteGate.class);
		OperationsRuntimeStatusService runtimeStatusService =
				mock(OperationsRuntimeStatusService.class);
		SystemAdminController controller = new SystemAdminController(
				administrationService,
				mock(CredentialTokenService.class),
				writeGate,
				runtimeStatusService);
		AccountPrincipal principal = new AccountPrincipal(
				1L,
				"system-admin",
				null,
				AccountSystemRole.SYSTEM_ADMIN,
				false);
		OperationsRuntimeStatus runtimeStatus = new OperationsRuntimeStatus(
				"test-version",
				Instant.parse("2026-08-05T00:00:00Z"),
				false,
				false,
				false,
				"장애 · DB 연결 확인 실패",
				new BackupRuntimeStatus(
						State.NOT_CONFIGURED, null, null, null, null));
		when(runtimeStatusService.currentAfterDatabaseFailure())
				.thenReturn(runtimeStatus);
		when(administrationService.operations(any())).thenThrow(
				new CannotCreateTransactionException(
						"jdbc:postgresql://user:secret@private-host/attend"));
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.operations(principal, model);

		assertThat(view).isEqualTo("admin/system/operations");
		assertThat(model.get("runtimeStatus")).isSameAs(runtimeStatus);
		assertThat(model.get("operationsUnavailable")).isEqualTo(true);
		assertThat(model.get("operations")).isInstanceOf(Map.class);
		Map<?, ?> operations = (Map<?, ?>) model.get("operations");
		assertThat(operations.values())
				.hasSize(5)
				.allSatisfy(value -> assertThat(value).isEqualTo("확인 불가"));
		assertThat(model.toString())
				.doesNotContain("secret", "private-host", "jdbc:");
		verify(runtimeStatusService).currentAfterDatabaseFailure();
		verify(administrationService).operations(principal.toActor());
	}
}
