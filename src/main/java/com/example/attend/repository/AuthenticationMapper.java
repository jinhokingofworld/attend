package com.example.attend.repository;

import com.example.attend.entity.Authentication;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthenticationMapper {
    //loadUserByUsername에 전달할 데이터 가져오기
    Authentication selectByUsername(String username);

    //회원 가입
}
