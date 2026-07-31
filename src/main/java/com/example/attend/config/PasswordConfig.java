package com.example.attend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 계정 비밀번호를 단방향 해시로 저장하기 위한 암호화 설정이다.
 */
@Configuration
public class PasswordConfig {

    /**
     * 문서에서 확정한 cost 12의 BCrypt encoder를 제공한다.
     *
     * <p>기본 cost 10보다 계산 비용을 높여 유출된 해시의 대입 공격 비용을
     * 증가시킨다. 원문 비밀번호를 복호화하는 기능은 제공하지 않는다.</p>
     *
     * @return 애플리케이션 공용 비밀번호 encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
