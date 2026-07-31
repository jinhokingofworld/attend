package com.example.attend.service;


import com.example.attend.entity.Member;

import java.util.List;

public interface MemberService {
    //모든 멤버 리스트 가져오기
    List<Member> getAllMem();
    //멤버 한 명 가져오기
    Member getMem(Long id);
    //멤버 추가하기
    void addMem(Member m);
    //멤버 수정하기
    void editMem(Member m);
}
