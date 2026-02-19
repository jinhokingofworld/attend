package com.example.attend.controller;

import com.example.attend.form.LoginForm;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

//@Controller
//@RequestMapping("/login")
public class LoginController {

    @GetMapping
    public String showLogin(@ModelAttribute LoginForm form) {
        return "login";
    }
}
