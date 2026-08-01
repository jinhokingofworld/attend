package com.example.attend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class MenuController {

    /** 공개 루트에서는 별도 레거시 메뉴 대신 역할 기반 관리자 진입점으로 이동한다. */
    @GetMapping
    public String showMenu() {
        return "redirect:/admin";
    }
}
