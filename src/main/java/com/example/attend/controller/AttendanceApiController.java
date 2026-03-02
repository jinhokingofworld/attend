package com.example.attend.controller;

import com.example.attend.entity.ApiResponse;
import com.example.attend.entity.TagRequest;
import com.example.attend.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceApiController {

    private final AttendanceService attendanceService;

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
    //Rest Api로써 ApiResponse를 통해 리턴을 통일 해주는게 좋음
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> makeAttendByJson(@RequestBody TagRequest request) {
        attendanceService.attendWithJson(request.getUid());
        return ResponseEntity.ok(ApiResponse.success());
    }


}
