/**
 * 관리자 화면에서 되돌리기 어려운 작업을 실수로 제출하지 않도록 확인한다.
 * 서버 권한 검사를 대체하지 않으며, JavaScript가 꺼져도 모든 form은 동작한다.
 */
document.addEventListener("submit", (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) {
        return;
    }

    const destructivePath = /\/(disable|revoke|exclude|cancel|remove|disconnect|deactivate)(\/|$)/;
    if (!destructivePath.test(form.action)) {
        return;
    }

    if (!window.confirm("이 작업은 출석 또는 운영 상태에 영향을 줍니다. 계속할까요?")) {
        event.preventDefault();
    }
});
