package com.example.attend.service.impl;

import com.example.attend.entity.Member;
import com.example.attend.repository.MemberMapper;
import com.example.attend.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberMapper mapper;

    @Override
    public List<Member> getAllMem() {
        return mapper.findAll();
    }

    @Override
    public Member getMem(Long id) {
        return mapper.findById(id);
    }

    @Override
    public void addMem(Member m) {
        mapper.insertMember(m);
    }

    @Override
    public void editMem(Member m) {
        mapper.updateMember(m);
    }

    @Override
    public void deleteMem(Long id) {
        mapper.deleteMember(id);
    }
}
