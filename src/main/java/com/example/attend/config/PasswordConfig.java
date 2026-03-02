package com.example.attend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        //인코딩 설정
        return new BCryptPasswordEncoder();
    }

    public String getEncodedPass(String pass) {
        return passwordEncoder().encode(pass);
    }
}
