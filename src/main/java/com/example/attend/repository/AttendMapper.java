package com.example.attend.repository;

import com.example.attend.entity.Attendance;
import com.example.attend.entity.AttendanceStatRaw;
import com.example.attend.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AttendMapper {
    void insert(Attendance attendance);

    void updateNote(@Param("id") Long id, @Param("note") String note);

    //uid로 member찾기
    Member selectMemberWithUID(@Param("uid") String uid);

    List<Attendance> selectDayList(@Param("today")LocalDate today);

    AttendanceStatRaw selectStatByMemberId(@Param("id") Long id);


}
