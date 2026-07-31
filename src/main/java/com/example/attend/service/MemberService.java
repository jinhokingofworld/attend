package com.example.attend.service;


import com.example.attend.entity.Member;

import java.util.List;

/**
 * 기존 교사 관리 화면이 사용하는 애플리케이션 서비스 계약이다.
 *
 * <p>컨트롤러는 저장 기술을 알지 않고 이 인터페이스에만 의존한다. 따라서
 * 교사 미존재 처리와 트랜잭션 같은 규칙을 웹 계층 밖에서 일관되게 적용할 수
 * 있다.</p>
 */
public interface MemberService {

    /**
     * 모든 교사를 조회한다.
     *
     * @return 저장된 교사 목록
     */
    List<Member> getAllMem();

    /**
     * 식별자로 교사 한 명을 조회한다.
     *
     * @param id 조회할 교사의 식별자
     * @return 조회된 교사
     * @throws com.example.attend.exception.MemberNotFoundException
     *         일치하는 교사가 없을 때
     */
    Member getMem(Long id);

    /**
     * 새 교사를 저장한다.
     *
     * @param m 저장할 교사
     */
    void addMem(Member m);

    /**
     * 기존 교사 정보를 갱신한다.
     *
     * @param m 식별자와 수정할 값을 가진 교사
     */
    void editMem(Member m);
}
