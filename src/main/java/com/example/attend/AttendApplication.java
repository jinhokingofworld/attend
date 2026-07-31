package com.example.attend;

import com.example.attend.config.AttendanceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AttendanceProperties.class)
public class AttendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AttendApplication.class, args);
	}

}
