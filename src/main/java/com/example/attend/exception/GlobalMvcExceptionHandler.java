package com.example.attend.exception;

import com.example.attend.common.error.AdminWritesDisabledException;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.DepartmentAccessDeniedException;
import com.example.attend.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.NoSuchElementException;

/**
 * 서버 렌더링 화면의 예외를 정보 노출 없는 상태 화면으로 변환한다.
 */
@ControllerAdvice(annotations = Controller.class)
public class GlobalMvcExceptionHandler {

    /**
     * 다른 부서 자원과 존재하지 않는 자원을 동일한 404로 처리한다.
     */
    @ExceptionHandler({
            NoSuchElementException.class,
            ResourceNotFoundException.class,
            DepartmentAccessDeniedException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound() {
        return "error/404";
    }

    /** 잘못된 path·query 형식을 400으로 처리한다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest() {
        return "error/400";
    }

    /** Bean validation 실패를 400으로 처리한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badArgument() {
        return "error/400";
    }

    /** 명령 입력 형식 오류를 400으로 처리한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String illegalArgument() {
        return "error/400";
    }

    /** 내부 상태 경합을 409로 처리한다. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String illegalState() {
        return "error/409";
    }

    /** DB 고유·참조 제약 위반을 일반 409 화면으로 숨긴다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String dataIntegrityViolation() {
        return "error/409";
    }

    /** 현재 업무 상태에서 실행할 수 없는 명령을 409로 처리한다. */
    @ExceptionHandler(BusinessRuleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String businessRule() {
        return "error/409";
    }

    /** 관리자 쓰기 feature가 꺼진 경우 재시도 가능한 503을 반환한다. */
    @ExceptionHandler(AdminWritesDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String adminWritesDisabled() {
        return "error/503";
    }

    //멤버 세부페이지에서 값을 찾을 수 없을 때
    @ExceptionHandler(MemberNotFoundException.class)
    public String memberNotFound(RedirectAttributes attributes) {
        attributes.addFlashAttribute("errorMessage", "대상 데이터가 없습니다.");
        return "redirect:/member";
    }

}
