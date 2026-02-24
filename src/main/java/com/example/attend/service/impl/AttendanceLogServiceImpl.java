package com.example.attend.service.impl;

import com.example.attend.entity.AttendanceLog;
import com.example.attend.repository.AttendMapper;
import com.example.attend.service.AttenanceLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceLogServiceImpl implements AttenanceLogService {

    @Autowired
    private AttendMapper attendMapper;

    // 항상 새 트랜잭션으로 독립 실행!
    // REQUIRES_NEW
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(AttendanceLog log) {
        attendMapper.insertAttendanceLog(log);
    }

    public String getLastFailedUid(){
        AttendanceLog attendanceLog = attendMapper.selectRecentFailedUids();
        return attendanceLog.getUid();
    }
}
