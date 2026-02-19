package com.example.attend.service;


import com.example.attend.entity.Attendance;
import com.example.attend.entity.AttendanceResult;
import com.example.attend.entity.TagRequest;

import java.util.List;

public interface AttendanceService {
//    public void attend(Long memberId);
    public void attendWithJson(String uid);
    public void writeNote(Long attendId, String note); //관리자 추가 이후에 사용
    List<Attendance> findTodayAttendance();
    AttendanceResult findMemberStat(Long id);
}
