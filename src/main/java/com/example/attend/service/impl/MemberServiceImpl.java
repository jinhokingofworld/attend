package com.example.attend.service.impl;

import com.example.attend.entity.Member;
import com.example.attend.exception.MemberNotFoundException;
import com.example.attend.repository.MemberMapper;
import com.example.attend.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link MemberService}의 MyBatis 기반 구현체다.
 *
 * <p>{@link Transactional}이 클래스에 적용되어 공개 메서드가 같은 트랜잭션
 * 경계에서 실행된다. 조회 결과가 없는 상황처럼 DB가 정상 결과로 취급하는
 * 경우도 이 계층에서 애플리케이션 예외로 변환한다.</p>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberMapper mapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Member> getAllMem() {
        return mapper.findAll();
    }

    /**
     * {@inheritDoc}
     *
     * <p>MyBatis는 행을 찾지 못하면 {@code null}을 반환하므로, 웹 계층이 저장
     * 기술의 반환 규칙을 알 필요가 없도록 도메인 예외로 바꾼다.</p>
     */
    @Override
    public Member getMem(Long id) throws MemberNotFoundException {
        Member m = mapper.findById(id);
        if (m == null) {
            throw new MemberNotFoundException();
        }
        return m;
    }

    /**
     * {@inheritDoc}
     *
     * <p>제약 조건 위반이나 연결 오류는 MyBatis와 Spring이 데이터 접근 예외로
     * 변환하므로, 여기서 같은 예외를 다시 만들어 던지지 않는다.</p>
     */
    @Override
    public void addMem(Member m) {
        mapper.insertMember(m);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void editMem(Member m) {
        mapper.updateMember(m);
    }
}
