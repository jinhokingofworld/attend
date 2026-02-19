package com.example.attend.repository;

import com.example.attend.entity.Authentication;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthenticationMapper {
    Authentication selectByUsername(String username);
}
