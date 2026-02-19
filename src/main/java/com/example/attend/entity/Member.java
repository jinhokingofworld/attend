package com.example.attend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Member {
    private Long id;
    private String name;
    private Integer age;
    private String phone;
    private LocalDate birth;
    private LocalDateTime createdAt;
    private String cardUid;
}
