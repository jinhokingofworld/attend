package com.example.attend.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

@Getter
@Setter
@ConfigurationProperties(prefix = "attendance")
public class AttendanceProperties {
    private LocalTime lateTime;

    @PostConstruct
    public void init() {
        System.out.println("lateTime = " + lateTime);
    }
}