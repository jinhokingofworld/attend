package com.example.attend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AttendanceResult {
    private int late;
    private float lateRatio;
    private int absent;
    private float absentRatio;

}
