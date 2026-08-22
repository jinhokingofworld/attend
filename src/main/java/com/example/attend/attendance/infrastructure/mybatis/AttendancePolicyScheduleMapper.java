package com.example.attend.attendance.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 정책 일정의 ON/OFF 상태와 반복 조건을 저장한다. */
@Mapper
public interface AttendancePolicyScheduleMapper {

    long insertSchedule(
            @Param("departmentId") long departmentId,
            @Param("policyVersionId") long policyVersionId,
            @Param("status") String status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("recurrence") String recurrence,
            @Param("intervalValue") int intervalValue,
            @Param("yearlyMonth") Integer yearlyMonth,
            @Param("yearlyDay") Integer yearlyDay,
            @Param("actorAccountId") long actorAccountId);

    void insertWeekdays(
            @Param("scheduleId") long scheduleId,
            @Param("weekdays") List<DayOfWeek> weekdays);

    void insertMonthdays(
            @Param("scheduleId") long scheduleId,
            @Param("monthdays") List<Integer> monthdays);

    PolicyScheduleRow lockSchedule(
            @Param("departmentId") long departmentId,
            @Param("scheduleId") long scheduleId);

    List<Integer> selectWeekdayValues(@Param("scheduleId") long scheduleId);

    List<Integer> selectMonthdays(@Param("scheduleId") long scheduleId);

    int updateStatus(
            @Param("departmentId") long departmentId,
            @Param("scheduleId") long scheduleId,
            @Param("status") String status,
            @Param("actorAccountId") long actorAccountId,
            @Param("archivedAt") Instant archivedAt);

    int replaceSchedule(
            @Param("departmentId") long departmentId,
            @Param("scheduleId") long scheduleId,
            @Param("policyVersionId") long policyVersionId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("recurrence") String recurrence,
            @Param("intervalValue") int intervalValue,
            @Param("yearlyMonth") Integer yearlyMonth,
            @Param("yearlyDay") Integer yearlyDay,
            @Param("actorAccountId") long actorAccountId);

    void deleteWeekdays(@Param("scheduleId") long scheduleId);

    void deleteMonthdays(@Param("scheduleId") long scheduleId);

}
