package com.example.attend.controller;

import com.example.attend.entity.AttendanceResult;
import com.example.attend.entity.Member;
import com.example.attend.form.MemberForm;
import com.example.attend.helper.MemberHelper;
import com.example.attend.service.AttenanceLogService;
import com.example.attend.service.AttendanceService;
import com.example.attend.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/member")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;
    private final AttendanceService attendanceService;
    private final AttenanceLogService attenanceLogService;

    //멤버 리스트 화면
    @GetMapping
    public String list(Model model) {
        model.addAttribute("members", memberService.getAllMem());
        return "/member/list"; //리스트 html페이지를 띄움
    }

    //멤버 세부 보기
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model,
                         RedirectAttributes attributes) {
        String lastUid = attenanceLogService.getLastFailedUid();
        Member member = memberService.getMem(id);
        AttendanceResult result = attendanceService.findMemberStat(id);

        if (member != null) {
            model.addAttribute("member", member);
            model.addAttribute("result", result);
            model.addAttribute("lastUid", lastUid);
            return "/member/detail";
        } else {
            attributes.addFlashAttribute("errorMessage", "대상 데이터가 없습니다.");
            return "/member/list";
        }
    }

    //멤버 등록 화면
    @GetMapping("/insert")
    //ModelAttribute가 뭐지?
    public String newMember(@ModelAttribute MemberForm form) {
        return "/member/insert";
    }

    //멤버 등록
    @PostMapping("/save")
    public String create(@Validated MemberForm form,
                         BindingResult bindingResult,
                         RedirectAttributes attributes) {
        if (bindingResult.hasErrors()) {
            return "/member/insert";
        }

        //엔티티로 변환
        Member m = MemberHelper.convertMember(form);
        memberService.addMem(m);
        attributes.addFlashAttribute("message", "새 멤버가 추가되었습니다.");
        return "redirect:/member";
    }

    //멤버 수정 화면
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model,
                            RedirectAttributes attributes) {
        Member m = memberService.getMem(id);
        if (m != null) {
            MemberForm form = MemberHelper.convertMemberForm(m);
            model.addAttribute("memberForm", form);
            return "member/edit";
        } else {
            attributes.addFlashAttribute("errorMessage", "대상 데이터가 없습니다.");
            return "redirect:/member";
        }
    }

    //멤버 수정
    @PostMapping("/update")
    public String update(@Validated MemberForm form,
                            BindingResult bindingResult,
                            RedirectAttributes attributes) {
        if (bindingResult.hasErrors()) {
            return "/member";
        }

        Member m = MemberHelper.convertMember(form);
        memberService.editMem(m);
        attributes.addFlashAttribute("message", "멤버가 업데이트 되었습니다.");
        return "redirect:/member";
    }

    //멤버 삭제
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes attributes) {
        memberService.deleteMem(id);
        attributes.addFlashAttribute("message", "회원"+ id + "이 삭제되었습니다.");
        return "redirect:/member";
    }
}
