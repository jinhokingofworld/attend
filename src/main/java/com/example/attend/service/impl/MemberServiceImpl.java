package com.example.attend.service.impl;

import com.example.attend.entity.Member;
import com.example.attend.exception.MemberNotFoundException;
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
    public Member getMem(Long id) throws MemberNotFoundException {
        Member m = mapper.findById(id);
        //멤버가 DB에 없다면 예외 발생, 서비스를 설계한 거여서 수동 예외처리
        if (m == null) {
            throw new MemberNotFoundException();
        }
        return m;
    }

    @Override
    public void addMem(Member m) {
        //DB가 예외를 자동으로 처리해주기 때문에 throw할 필요 없음
        mapper.insertMember(m);
    }

    @Override
    public void editMem(Member m) {
        mapper.updateMember(m);
    }
}
