package com.example.attend.service;

import com.example.attend.entity.AttendanceLog;

public interface AttenanceLogService {
    void save(AttendanceLog log);
    String getLastFailedUid();
}
