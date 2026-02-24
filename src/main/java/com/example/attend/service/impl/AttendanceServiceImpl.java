package com.example.attend.service.impl;

import com.example.attend.config.AttendanceProperties;
import com.example.attend.entity.*;
import com.example.attend.exception.AlreadyAttendedException;
import com.example.attend.exception.MemberNotFoundException;
import com.example.attend.repository.AttendMapper;
import com.example.attend.service.AttenanceLogService;
import com.example.attend.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    public final AttendMapper attendMapper;
    public final AttendanceProperties attendanceProperties;
    public final AttenanceLogService attenanceLogService;

//    @Override
//    public void attend(Long memberId) throws AlreadyAttendedException {
//        //status 판별
//        LocalDateTime now = LocalDateTime.now();
//        LocalTime lateTime = attendanceProperties.getLateTime();
//        LocalDate today = LocalDate.now();
//        boolean isLate = now.toLocalTime().isAfter(lateTime);
//        AttendStatus status;
//
//        if (isLate) { //지각
//            status = AttendStatus.TIME_OUT;
//        } else {
//            status = AttendStatus.IN_TIME;
//        }
//
//        //매퍼에게 전달해줄 객체 생성
//        //중복 불가 처리
//        Attendance attendance = new Attendance(memberId, status, now, today);
//        try {
//            attendMapper.insert(attendance);
//        } catch (DuplicateKeyException e) {
//            throw new AlreadyAttendedException();
//        }
//    }

    @Override
    public void writeNote(Long attendId, String note) {
        attendMapper.updateNote(attendId, note);
    }

    @Override
    public List<Attendance> findTodayAttendance() {
        LocalDate today = LocalDate.now();
        return attendMapper.selectDayList(today);
    }


    public AttendanceResult findMemberStat(Long id) {
        AttendanceStatRaw raw = attendMapper.selectStatByMemberId(id);
        int total = raw.getTotalCnt();
        int absent = raw.getAbsentCnt();
        int late = raw.getLateCnt();

        if (total == 0) {
            return new AttendanceResult(0, 0.0f, 0, 0.0f);
        }

        float lateRatio = (late * 100.0f) / total;
        float absentRatio = (absent * 100.0f) / total;

        return new AttendanceResult(late, lateRatio, absent, absentRatio);
    }

    public void attendWithJson(String uid) throws AlreadyAttendedException, MemberNotFoundException {
        Member m = attendMapper.selectMemberWithUID(uid);
        //UID에 해당하는 Member를 찾지 못함
        if (m == null) {
            attenanceLogService.save(AttendanceLog.fail(uid, null, "MemberNotFoundException",
                    "UID에 해당하는 멤버를 찾을 수 없습니다."));
//            attendMapper.insertAttendanceLog(
//                AttendanceLog.fail(uid, null, "MemberNotFoundException",
//                        "UID에 해당하는 멤버를 찾을 수 없습니다."));
            throw new MemberNotFoundException();
        }

        //status 판별
        LocalDateTime now = LocalDateTime.now();
        LocalTime lateTime = attendanceProperties.getLateTime();
        LocalDate today = now.toLocalDate();
        boolean isLate = now.toLocalTime().isAfter(lateTime);
        AttendStatus status;

        if (isLate) { //지각
            status = AttendStatus.TIME_OUT;
        } else {
            status = AttendStatus.IN_TIME;
        }

        //매퍼에게 전달해줄 객체 생성
        Attendance attendance = new Attendance(m.getId(), status, now, today);

        //출석과 로깅
        try {
            attendMapper.insert(attendance);
            attenanceLogService.save(AttendanceLog.success(uid, m.getId()));
//            attendMapper.insertAttendanceLog(AttendanceLog.success(uid, m.getId()));
        } catch (DuplicateKeyException e) {
            attenanceLogService.save(AttendanceLog.fail(uid, m.getId(), "DuplicateKeyException",
                    "오늘 이미 출석된 UID입니다."));
//            attendMapper.insertAttendanceLog(
//                    AttendanceLog.fail(uid, m.getId(), "DuplicateKeyException",
//                            "오늘 이미 출석된 UID입니다."));
            throw new AlreadyAttendedException();
        }
    }
}
