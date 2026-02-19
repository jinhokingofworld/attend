package com.example.attend.service.impl;

import com.example.attend.entity.Authentication;
import com.example.attend.entity.LoginUser;
import com.example.attend.entity.Role;
import com.example.attend.repository.AuthenticationMapper;
import lombok.RequiredArgsConstructor;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LoginUserDetailServiceImpl implements UserDetailsService {

    private final AuthenticationMapper authenticationMapper;

    @Override
    public UserDetails loadUserByUsername(String username)
        throws UsernameNotFoundException {

        Authentication authentication = authenticationMapper.selectByUsername(username);

        // 사용자명으로 "minsoo"가 입력되면 UserDetails 구현 클래스를 반환
        if (username != null) {
            //대상 데이터가 존재
            //UserDetails의 구현 클래스를 반환
            return new LoginUser(authentication.getUsername(),
                    authentication.getPassword(),
                    getAuthorityList(authentication.getAuthority()),
                    authentication.getDisplayname()
            );
        } else {
            throw new UsernameNotFoundException(
                    username + " => 사용자명이 존재하지 않습니다.");
        }
    }

    private List<GrantedAuthority> getAuthorityList(Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.name()));
        if (role == Role.ADMIN) {
            authorities.add(
                    new SimpleGrantedAuthority(Role.USER.toString()));
        }
        return authorities;
    }
}
