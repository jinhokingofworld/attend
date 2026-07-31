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

/**
 * 기존 교사 목록·상세·등록·수정 화면의 Spring MVC 요청을 연결한다.
 *
 * <p>{@link Controller}는 메서드의 반환 문자열을 HTTP 응답 본문이 아니라
 * Thymeleaf 템플릿 이름 또는 리다이렉트 경로로 해석한다. 컨트롤러는 입력 검증과
 * 화면 전환만 담당하고, 데이터 조회·변경은 {@link MemberService}에 위임한다.</p>
 *
 * <p>이 컨트롤러는 부서 개념이 도입되기 전의 기존 화면을 유지하는 코드다.
 * 앞으로 부서별 관리자 기능을 구현할 때는 URL과 서비스 호출에 부서 범위를
 * 명시적으로 포함해야 한다.</p>
 */
@Controller
@RequestMapping("/member")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;
    private final AttendanceService attendanceService;
    private final AttenanceLogService attenanceLogService;

    /**
     * 모든 교사를 조회해 목록 화면에 전달한다.
     *
     * @param model 뷰에 전달할 데이터를 담는 Spring MVC 모델
     * @return 교사 목록 템플릿 경로
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("members", memberService.getAllMem());
        return "/member/list";
    }

    /**
     * 교사 한 명과 현재 출석 상태, 마지막으로 인식에 실패한 NFC UID를 조회한다.
     *
     * <p>교사가 없을 때의 예외 변환은 컨트롤러가 아니라 서비스와 전역 예외
     * 처리기가 담당한다.</p>
     *
     * @param id 조회할 교사의 식별자
     * @param model 상세 화면에 전달할 데이터를 담는 모델
     * @param attributes 리다이렉트 메시지용 객체이며 현재 구현에서는 사용하지 않는다
     * @return 교사 상세 템플릿 경로
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model,
                         RedirectAttributes attributes) {
        String lastUid = attenanceLogService.getLastFailedUid();
        Member member = memberService.getMem(id);
        AttendanceResult result = attendanceService.findMemberStat(id);

        model.addAttribute("member", member);
        model.addAttribute("result", result);
        model.addAttribute("lastUid", lastUid);
        return "/member/detail";
    }


    /**
     * 빈 교사 등록 폼을 준비해 등록 화면을 표시한다.
     *
     * <p>{@link ModelAttribute}가 붙은 {@code MemberForm}은 Spring이 생성해 모델에
     * 넣으므로, 템플릿은 별도의 {@link Model} 인자 없이도 폼 객체를 사용할 수 있다.</p>
     *
     * @param form Spring이 생성하거나 요청 값으로 바인딩한 등록 폼
     * @return 교사 등록 템플릿 경로
     */
    @GetMapping("/insert")
    public String newMember(@ModelAttribute MemberForm form) {
        return "/member/insert";
    }

    /**
     * 등록 폼을 검증한 뒤 교사 엔티티로 변환해 저장한다.
     *
     * <p>{@link BindingResult}는 반드시 검증 대상 바로 다음 인자로 받아야 Spring이
     * 검증 오류를 예외로 끝내지 않고 이 메서드에 전달한다.</p>
     *
     * @param form 사용자 입력과 검증 규칙을 담은 등록 폼
     * @param bindingResult 폼 바인딩 및 검증 결과
     * @param attributes 리다이렉트 후 한 번만 보여 줄 메시지를 담는 저장소
     * @return 오류가 있으면 등록 화면, 성공하면 교사 목록으로 이동하는 경로
     */
    @PostMapping("/save")
    public String create(@Validated MemberForm form,
                         BindingResult bindingResult,
                         RedirectAttributes attributes) {
        if (bindingResult.hasErrors()) {
            return "/member/insert";
        }

        Member m = MemberHelper.convertMember(form);
        memberService.addMem(m);
        attributes.addFlashAttribute("message", "새 멤버가 추가되었습니다.");
        return "redirect:/member";
    }

    /**
     * 저장된 교사 정보를 수정 폼으로 변환해 편집 화면에 전달한다.
     *
     * @param id 수정할 교사의 식별자
     * @param model 변환된 폼을 담는 Spring MVC 모델
     * @param attributes 리다이렉트 메시지용 객체이며 현재 구현에서는 사용하지 않는다
     * @return 교사 수정 템플릿 경로
     */
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model,
                            RedirectAttributes attributes) {
        Member m = memberService.getMem(id);
        MemberForm form = MemberHelper.convertMemberForm(m);
        model.addAttribute("memberForm", form);
        return "member/edit";
    }

    /**
     * 수정 폼을 검증한 뒤 기존 교사 정보를 갱신한다.
     *
     * @param form 식별자와 수정할 값을 담은 폼
     * @param bindingResult 폼 바인딩 및 검증 결과
     * @param attributes 리다이렉트 후 표시할 일회성 메시지 저장소
     * @return 검증 실패 시 현재 회원 화면, 성공 시 교사 목록 리다이렉트 경로
     */
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

}
