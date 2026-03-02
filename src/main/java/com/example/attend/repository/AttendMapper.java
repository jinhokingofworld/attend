package com.example.attend.repository;

import com.example.attend.entity.Attendance;
import com.example.attend.entity.AttendanceLog;
import com.example.attend.entity.AttendanceStatRaw;
import com.example.attend.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AttendMapper {
    //출석체크
    void insert(Attendance attendance);

    //관리자가 결석 사유 입력
    void updateNote(@Param("id") Long id, @Param("note") String note);

    //uid로 member찾기
    Member selectMemberWithUID(@Param("uid") String uid);

    //오늘의 출석 불러오기
    List<Attendance> selectDayList(@Param("today")LocalDate today);

    //개인별 출석 통계
    AttendanceStatRaw selectStatByMemberId(@Param("id") Long id);

    //로그 저장
    void insertAttendanceLog(AttendanceLog log);

    //가장 최근 실패 uid 가져오기
    AttendanceLog selectRecentFailedUids();

}
