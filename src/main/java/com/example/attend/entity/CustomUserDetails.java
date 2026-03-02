package com.example.attend.entity;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

//✔ Security가 이해할 수 있는 형식으로 변환
//✔ DB 엔티티 → Security 객체로 변환하는 어댑터 역할
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final Authentication authentication;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
                //Spring Security는 "ROLE_" 접두어를 원한다
                new SimpleGrantedAuthority(
                        "ROLE_" + authentication.getAuthority().name()
                )
        );
    }

    @Override
    public String getPassword() {
        return authentication.getPassword();
    }

    @Override
    public String getUsername() {
        return authentication.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
