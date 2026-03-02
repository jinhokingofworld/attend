package com.example.attend.controller;

import com.example.attend.service.AttendanceService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller //Rest APi 사용하는 컨트롤러
@RequestMapping("/attendance")
@RequiredArgsConstructor
public class AttendancePageController {
    //DI
    private final AttendanceService attendanceService;

    @GetMapping
    public String button(){
        return "/attendance/form";
    }

    @GetMapping("/today")
    public String showDay(Model model) {
        model.addAttribute("list", attendanceService.findTodayAttendance());
        return "/attendance/today";
    }
}
