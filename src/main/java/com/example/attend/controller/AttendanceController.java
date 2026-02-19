package com.example.attend.controller;

import com.example.attend.config.AttendanceProperties;
import com.example.attend.entity.ApiResponse;
import com.example.attend.entity.TagRequest;
import com.example.attend.exception.AlreadyAttendedException;
import com.example.attend.exception.MemberNotFoundException;
import com.example.attend.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/attendance")
@RequiredArgsConstructor
public class AttendanceController {
    //DI
    private final AttendanceService attendanceService;

    @GetMapping
    public String button(){
        return "/attendance/form";
    }

//    @PostMapping("/{memberId}")
//    public String makeAttend(@PathVariable Long memberId,
//                             RedirectAttributes attributes) {
//        try {
//            attendanceService.attend(memberId);
//            attributes.addFlashAttribute("message", "출석체크가 성공적으로 완료되었습니다.");
//        } catch (AlreadyAttendedException e) {
//            attributes.addFlashAttribute("error", "이미 출석되었습니다.");
//        }
//        return "redirect:/attendance/today";
//    }

    //출석체크 json형식
//    @PostMapping("/api")
//    public String makeAttendByJson(@RequestBody TagRequest request,
//                                   RedirectAttributes attributes) {
//        try {
//            attendanceService.attendWithJson(request.getUid());
//            attributes.addFlashAttribute("message", "출석체크가 성공적으로 완료되었습니다.");
//        } catch (AlreadyAttendedException e) {
//            attributes.addFlashAttribute("error", "이미 출석되었습니다.");
//            return "redirect:/member/list";
//        } catch (MemberNotFoundException f) {
//            attributes.addFlashAttribute("error", "등록되지 않은 카드입니다.");
//            return "redirect:/member/list";
//        }
//        return "redirect:/attendance/today";
//    }

    //출석체크
    @PostMapping("/api")
    public ResponseEntity<ApiResponse> makeAttendByJson(@RequestBody TagRequest request) {
        try {
            attendanceService.attendWithJson(request.getUid());
            return ResponseEntity.ok(new ApiResponse("Success"));
        } catch (AlreadyAttendedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse("ALREADY_ATTENDED"));
        } catch (MemberNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse("ALREADY_ATTENDED"));
        }
    }

    @GetMapping("/today")
    public String showDay(Model model) {
        model.addAttribute("list", attendanceService.findTodayAttendance());
        return "/attendance/today";
    }


}
