package com.example.attend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                //http 요청에 대한 보안 설정
                .authorizeHttpRequests(authz -> authz
                    // /login은 인증이 필요하지 않음
                    .requestMatchers("/login").permitAll()
                    //그 밖의 요청은 인증이 필요
                    .anyRequest().authenticated())
                //폼 기반의 로그인 설정
                .formLogin(form -> form
                    //커스텀 로그인 페이지 설정
                    .loginPage("/login")
                        //로그인 요청 Post 주소
                        .loginProcessingUrl("/authentication")
                        //ID와 PassWord 매핑
                        .usernameParameter("usernameInput")
                        .passwordParameter("passwordInput")
                        //로그인 성공, 실패 후 보낼 페이지
                        .defaultSuccessUrl("/member")
                        .failureUrl("/login?error"))
                    .logout(logout -> logout
                            //로그아웃 요청 POST 주소
                            .logoutUrl("/logout")
                            //성공 위치
                            .logoutSuccessUrl("/login?logout")
                            //HTTP 세션 무효화
                            .invalidateHttpSession(true)
                            //세션 식별용 쿠키 삭제
                            .deleteCookies("JSESSIONID")
                );
        return http.build();
    }

}
