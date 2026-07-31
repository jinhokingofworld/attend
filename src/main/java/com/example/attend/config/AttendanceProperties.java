package com.example.attend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

@Getter
@Setter
@ConfigurationProperties(prefix = "attendance")
public class AttendanceProperties {
    private LocalTime lateTime;

}
