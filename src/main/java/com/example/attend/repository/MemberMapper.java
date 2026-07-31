package com.example.attend.repository;

import com.example.attend.entity.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 기존 {@code member} 테이블을 조회·변경하는 MyBatis 매퍼다.
 *
 * <p>구현 클래스를 직접 작성하지 않아도 MyBatis가 이 인터페이스와 매핑 XML을
 * 연결한 프록시 객체를 런타임에 생성한다. SQL 실행 결과를 비즈니스 의미로
 * 해석하는 일은 서비스 계층의 책임이다.</p>
 */
@Mapper
public interface MemberMapper {

    /**
     * 저장된 모든 교사를 조회한다.
     *
     * @return 매핑 XML의 정렬 기준으로 조회된 교사 목록
     */
    List<Member> findAll();

    /**
     * 식별자로 교사 한 명을 조회한다.
     *
     * @param id 조회할 교사의 식별자. {@link Param} 이름은 매핑 XML의
     *           {@code #{id}}와 연결된다.
     * @return 일치하는 교사, 존재하지 않으면 {@code null}
     */
    Member findById(@Param("id") Long id);

    /**
     * 새 교사를 저장한다.
     *
     * @param m 저장할 교사 엔티티
     */
    void insertMember(Member m);

    /**
     * 교사 식별자에 해당하는 행을 전달받은 값으로 갱신한다.
     *
     * @param m 식별자와 수정할 값을 가진 교사 엔티티
     */
    void updateMember(Member m);
}
