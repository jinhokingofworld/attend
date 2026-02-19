package com.example.attend;

import com.example.attend.config.AttendanceProperties;
import com.example.attend.entity.Member;
import com.example.attend.repository.MemberMapper;
import com.example.attend.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.LocalDate;

@SpringBootApplication
@EnableConfigurationProperties(AttendanceProperties.class)
@RequiredArgsConstructor //의존성 주입
public class AttendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AttendApplication.class, args);
	}

    //Repository의 Mapper 객체를 DI
//    private final MemberMapper mapper;
//    private final MemberService service;

//    private void exe2() {
//        System.out.println("===전체 검색===");
//        for (Member m : service.getAllMem())
//            System.out.println(m);
//        System.out.println();
//
//        System.out.println("===단일 검색===");
//        System.out.println(service.getMem(1L));
//        System.out.println();
//
//        Member m1 = new Member();
//        m1.setName("고윤정");
//        m1.setAge(100);
//        m1.setPhone("010-3909-1021");
//        m1.setBirth(LocalDate.of(1994,3,20));
//        service.addMem(m1);
//        System.out.println("===등록 확인===");
//        System.out.println(service.getMem(5L));
//        System.out.println();
//
//        m1 = service.getMem(5L);
//        m1.setName("김바보");
//        service.editMem(m1);
//        System.out.println("===업데이트 확인===");
//        System.out.println(service.getMem(5L));
//        System.out.println();
//
//        service.deleteMem(5L);
//        System.out.println("===삭제 확인===");
//        if (service.getMem(5L) == null)
//            System.out.println("정상적으로 삭제되었습니다.");
//    }
//
//    private void exe() {
//
//        System.out.println("===전체 검색===");
//        for (Member m : mapper.findAll())
//            System.out.println(m);
//        System.out.println();
//
//        System.out.println("===단일 검색===");
//        System.out.println(mapper.findById(1L));
//        System.out.println();
//
//        Member m1 = new Member();
//        m1.setName("고윤정");
//        m1.setAge(100);
//        m1.setPhone("010-3909-1021");
//        m1.setBirth(LocalDate.of(1994,3,20));
//        mapper.insertMember(m1);
//        System.out.println("===등록 확인===");
//        System.out.println(mapper.findById(5L));
//        System.out.println();
//
//        m1.setName("김바보");
//        mapper.updateMember(m1);
//        System.out.println("===업데이트 확인===");
//        System.out.println(mapper.findById(5L));
//        System.out.println();
//
//        mapper.deleteMember(5L);
//        System.out.println("===삭제 확인===");
//        for (Member mem : mapper.findAll())
//            System.out.println(mem);
//    }


}
