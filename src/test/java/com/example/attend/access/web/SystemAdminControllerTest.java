package com.example.attend.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.application.DepartmentAdminInvitationService;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.domain.AccountSystemRole;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.operations.BackupRuntimeStatus;
import com.example.attend.operations.BackupRuntimeStatus.State;
import com.example.attend.operations.OperationsRuntimeStatus;
import com.example.attend.operations.OperationsRuntimeStatusService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

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
				mock(DepartmentAdminInvitationService.class),
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

	/** 부서 이름·활성 상태 command가 각각 올바른 서비스와 redirect를 사용한다. */
	@Test
	void routesDepartmentLifecycleCommands() {
		SystemAdministrationService administration = mock(SystemAdministrationService.class);
		SystemAdminController controller = controller(administration,
				mock(DepartmentAdminInvitationService.class));
		AccountPrincipal principal = systemAdmin();

		RedirectAttributesModelMap rename = new RedirectAttributesModelMap();
		assertThat(controller.editDepartment(principal, 7L, "Youth", rename))
				.isEqualTo("redirect:/admin/system/departments/7");
		verify(administration).updateDepartment(principal.toActor(), 7L, "Youth");
		assertThat(rename.getFlashAttributes().get("message"))
				.isEqualTo("부서 이름을 수정했습니다.");

		RedirectAttributesModelMap deactivate = new RedirectAttributesModelMap();
		assertThat(controller.deactivateDepartment(principal, 7L, deactivate))
				.isEqualTo("redirect:/admin/system/departments/7");
		verify(administration).deactivateDepartment(principal.toActor(), 7L);

		RedirectAttributesModelMap reactivate = new RedirectAttributesModelMap();
		assertThat(controller.reactivateDepartment(principal, 7L, reactivate))
				.isEqualTo("redirect:/admin/system/departments/7");
		verify(administration).reactivateDepartment(principal.toActor(), 7L);
	}

	/** 시스템 관리자의 초대 생성·재전송 command가 시스템 범위로 전달된다. */
	@Test
	void routesDepartmentInvitationAndResendCommands() {
		DepartmentAdminInvitationService invitations = mock(DepartmentAdminInvitationService.class);
		SystemAdminController controller = controller(mock(SystemAdministrationService.class), invitations);
		AccountPrincipal principal = systemAdmin();

		assertThat(controller.inviteDepartmentAdministrator(
				principal, 7L, "admin@example.com", new RedirectAttributesModelMap()))
				.isEqualTo("redirect:/admin/system/departments/7");
		verify(invitations).invite(principal.toActor(), 7L, "admin@example.com", true);

		assertThat(controller.resendDepartmentAdministratorInvitation(
				principal, 7L, 19L, new RedirectAttributesModelMap()))
				.isEqualTo("redirect:/admin/system/departments/7");
		verify(invitations).resend(principal.toActor(), 7L, 19L, true);
	}

	/** 비활성 부서 초대 거부는 성공 flash 없이 같은 상세 화면으로 돌아간다. */
	@Test
	void reportsInactiveDepartmentInvitationFailure() {
		DepartmentAdminInvitationService invitations = mock(DepartmentAdminInvitationService.class);
		AccountPrincipal principal = systemAdmin();
		doThrow(new BusinessRuleException("inactive department cannot send invitations"))
				.when(invitations).invite(eq(principal.toActor()), eq(7L),
						eq("admin@example.com"), eq(true));
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		assertThat(controller(mock(SystemAdministrationService.class), invitations)
				.inviteDepartmentAdministrator(principal, 7L, "admin@example.com", redirect))
				.isEqualTo("redirect:/admin/system/departments/7");
		assertThat(redirect.getFlashAttributes().get("error"))
				.isEqualTo("inactive department cannot send invitations");
		assertThat(redirect.getFlashAttributes()).doesNotContainKey("message");
	}

	private static SystemAdminController controller(
			SystemAdministrationService administration,
			DepartmentAdminInvitationService invitations) {
		return new SystemAdminController(administration, invitations,
				mock(CredentialTokenService.class), mock(AdminWriteGate.class),
				mock(OperationsRuntimeStatusService.class));
	}

	private static AccountPrincipal systemAdmin() {
		return new AccountPrincipal(1L, "system-admin", null,
				AccountSystemRole.SYSTEM_ADMIN, false);
	}
}
