package com.example.attend.entity;

import lombok.Data;

@Data
public class AttendanceStatRaw {
    private int totalCnt;
    private int lateCnt;
    private int absentCnt;
}
