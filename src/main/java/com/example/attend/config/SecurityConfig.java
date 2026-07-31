package com.example.attend.config;

import com.example.attend.access.security.AbsoluteSessionTimeoutFilter;
import com.example.attend.access.security.LoginRateLimitFilter;
import com.example.attend.access.security.PublicCredentialRateLimitFilter;
import com.example.attend.access.security.SensitiveResponseHeaderFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * 브라우저 관리자 요청과 장치 API 요청의 보안 경계를 분리한다.
 *
 * <p>웹 요청은 폼 로그인, 서버 세션, CSRF 방어를 사용한다. 장치 API는 M4에서
 * HMAC 인증을 추가할 예정이며, 그 전까지는 모든 요청을 명시적으로 거부한다.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 아직 인증 프로토콜이 구현되지 않은 장치 API를 외부에 노출하지 않는다.
     *
     * @param http Spring Security 설정 객체
     * @return 장치 API 전용 stateless filter chain
     * @throws Exception 보안 설정 생성에 실패한 경우
     */
    @Bean
    @Order(1)
    public SecurityFilterChain deviceSecurityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .securityMatcher("/api/v1/device/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(deviceAuthenticationEntryPoint()))
                .authorizeHttpRequests(authz -> authz
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * 관리자 웹과 기존 화면에 적용할 세션 기반 보안 정책을 만든다.
     *
     * @param http Spring Security 설정 객체
     * @param clock 절대 세션 만료 계산에 사용할 서버 시계
     * @return 브라우저 요청용 filter chain
     * @throws Exception 보안 설정 생성에 실패한 경우
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(
            HttpSecurity http,
            Clock clock) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(
                                "/login",
                                "/authentication",
                                "/account/setup",
                                "/account/password-reset",
                                "/error").permitAll()
                        .requestMatchers("/admin/system/**")
                                .hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/admin/departments/**")
                                .hasRole("DEPARTMENT_ADMIN")
                        .requestMatchers(
                                "/admin",
                                "/admin/workspaces",
                                "/admin/account/**")
                                .authenticated()
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/authentication")
                        .usernameParameter("usernameInput")
                        .passwordParameter("passwordInput")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/login?error"))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(
                        new LoginRateLimitFilter(clock),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new PublicCredentialRateLimitFilter(clock),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new AbsoluteSessionTimeoutFilter(clock),
                        SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new SensitiveResponseHeaderFilter(),
                        AbsoluteSessionTimeoutFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint deviceAuthenticationEntryPoint() {
        return new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    }
}
