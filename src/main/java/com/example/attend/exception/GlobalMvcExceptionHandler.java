package com.example.attend.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.ui.Model;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.NoSuchElementException;

@ControllerAdvice(annotations = Controller.class)
public class GlobalMvcExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public String notFound() {
        return "error/404";
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String badRequest() {
        return "error/400";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public String badArgument() {
        return "error/400";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String illegalArgument() {
        return "error/400";
    }

    @ExceptionHandler(IllegalStateException.class)
    public String illegalState() {
        return "error/409";
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String dataIntegrityViolation() {
        return "error/409";
    }

    //멤버 세부페이지에서 값을 찾을 수 없을 때
    @ExceptionHandler(MemberNotFoundException.class)
    public String memberNotFound(RedirectAttributes attributes) {
        attributes.addFlashAttribute("errorMessage", "대상 데이터가 없습니다.");
        return "redirect:/member";
    }

}
