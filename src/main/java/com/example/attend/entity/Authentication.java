package com.example.attend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Authentication {
    //회원가입 DTO
    private String username;
    private String password;
    private Role authority;
}
