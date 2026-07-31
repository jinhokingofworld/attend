package com.example.attend.controller;

import com.example.attend.form.LoginForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
@RequiredArgsConstructor
public class LoginController {
    //private final UserService userService;

    @GetMapping("/login")
    public String showLogin(@ModelAttribute LoginForm form) {
        return "login";
    }

    //회원가입
//    @GetMapping("/signin")
//    public String showSignin(@ModelAttribute SigninForm form) {
//        return "/signin";
//    }

}
