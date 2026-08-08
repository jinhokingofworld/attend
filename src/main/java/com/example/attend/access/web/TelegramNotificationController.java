package com.example.attend.access.web;

import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.notification.application.TelegramConnectionService;
import com.example.attend.notification.application.TelegramConnectionView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 로그인한 관리자 본인의 Telegram 알림 연결 화면이다. */
@Controller
public final class TelegramNotificationController {
    private final TelegramConnectionService connectionService;

    public TelegramNotificationController(TelegramConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping("/admin/account/notifications")
    public String form(
            @AuthenticationPrincipal AccountPrincipal principal,
            Model model) {
        addView(model, connectionService.view(principal.accountId()), null);
        return "admin/account-notifications";
    }

    /** raw link는 이 응답의 HTML에만 넣고 redirect URL이나 flash에 저장하지 않는다. */
    @PostMapping("/admin/account/notifications/telegram/link")
    public String issueLink(
            @AuthenticationPrincipal AccountPrincipal principal,
            Model model) {
        try {
            String linkUrl = connectionService.issueLink(principal.accountId());
            addView(model, connectionService.view(principal.accountId()), linkUrl);
        } catch (RuntimeException exception) {
            addView(model, connectionService.view(principal.accountId()), null);
            model.addAttribute("error", safeMessage(exception));
        }
        return "admin/account-notifications";
    }

    @PostMapping("/admin/account/notifications/telegram/disconnect")
    public String disconnect(
            @AuthenticationPrincipal AccountPrincipal principal,
            RedirectAttributes redirect) {
        try {
            connectionService.disconnect(principal.accountId());
            redirect.addFlashAttribute("message", "Telegram 연결을 해제했습니다.");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/account/notifications";
    }

    @PostMapping("/admin/account/notifications/telegram/test")
    public String test(
            @AuthenticationPrincipal AccountPrincipal principal,
            RedirectAttributes redirect) {
        try {
            connectionService.requestTestMessage(principal.accountId());
            redirect.addFlashAttribute("message", "시험 메시지 발송을 요청했습니다.");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/account/notifications";
    }

    private static void addView(Model model, TelegramConnectionView view, String linkUrl) {
        model.addAttribute("connection", view);
        model.addAttribute("linkUrl", linkUrl);
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? "요청을 처리하지 못했습니다."
                : exception.getMessage();
    }
}
