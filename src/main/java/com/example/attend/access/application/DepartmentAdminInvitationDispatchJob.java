package com.example.attend.access.application;

/** SMTP worker가 전달할 최소 초대 작업 정보다. token 원문은 포함하지 않는다. */
public record DepartmentAdminInvitationDispatchJob(
        long id,
        long accountId,
        long departmentId,
        long issuedByAccountId,
        String departmentName,
        String recipientEmail,
        String deliveryType,
        int attemptCount,
        long claimVersion,
        boolean eligible) {
}
