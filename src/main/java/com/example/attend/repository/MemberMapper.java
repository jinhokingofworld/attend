package com.example.attend.repository;

import com.example.attend.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemberMapper {
    //모든 멤버 리스트 가져오기
    List<Member> findAll();

    //멤버 한명 가져오기
    Member findById(@Param("id") Long id);

    //멤버 추가하기
    void insertMember(Member m);

    //멤버 수정하기
    void updateMember(Member m);

    //멤버 삭제하기
    void deleteMember(@Param("id") Long id);
}
