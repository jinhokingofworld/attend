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

/** 멤버 표의 어느 셀을 선택해도 상세 화면으로 이동하게 한다. */
document.querySelectorAll("[data-row-href]").forEach((row) => {
    const navigate = () => {
        window.location.assign(row.dataset.rowHref);
    };

    row.addEventListener("click", (event) => {
        if (event.target.closest("a, button, input, select, textarea")) {
            return;
        }
        navigate();
    });
    row.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            navigate();
        }
    });
});

const dashboardRoot = document.querySelector("[data-dashboard-live]");
if (dashboardRoot) {
    const dashboardContent = dashboardRoot.querySelector("[data-dashboard-content]");
    const dashboardEmpty = dashboardRoot.querySelector("[data-dashboard-empty]");
    const rowsBody = dashboardRoot.querySelector("[data-attendance-rows]");
    const filterEmpty = dashboardRoot.querySelector("[data-filter-empty]");
    const filterTitle = dashboardRoot.querySelector("[data-filter-title]");
    const filterButtons = [...dashboardRoot.querySelectorAll("[data-attendance-filter]")];
    const filterLabels = {
        ALL: "대상 교사",
        PRESENT: "정상 출석 교사",
        LATE: "지각 교사",
        ABSENT: "결석 교사",
        PENDING: "미기록 교사"
    };
    const statusLabels = {
        PRESENT: "정상",
        LATE: "지각",
        ABSENT: "결석",
        PENDING: "미기록"
    };
    let selectedFilter = "ALL";
    let pollingPaused = false;

    const applyFilter = (filter) => {
        selectedFilter = filter;
        let visibleCount = 0;
        rowsBody.querySelectorAll("tr").forEach((row) => {
            const visible = filter === "ALL" || row.dataset.attendanceStatus === filter;
            row.hidden = !visible;
            if (visible) {
                visibleCount += 1;
            }
        });
        filterButtons.forEach((button) => {
            button.setAttribute("aria-pressed", String(button.dataset.attendanceFilter === filter));
        });
        filterTitle.textContent = filterLabels[filter];
        filterEmpty.hidden = visibleCount !== 0;
    };

    filterButtons.forEach((button) => {
        button.addEventListener("click", () => applyFilter(button.dataset.attendanceFilter));
    });

    const cell = (textValue) => {
        const element = document.createElement("td");
        element.textContent = textValue ?? "-";
        return element;
    };

    const renderRows = (rows) => {
        rowsBody.replaceChildren();
        rows.forEach((row) => {
            const status = row.status ?? "PENDING";
            const tableRow = document.createElement("tr");
            tableRow.dataset.attendanceStatus = status;

            const nameCell = document.createElement("td");
            const link = document.createElement("a");
            link.href = `${dashboardRoot.dataset.memberBase}/${row.member_id}`;
            link.textContent = row.name;
            nameCell.append(link);
            tableRow.append(
                nameCell,
                cell(statusLabels[status] ?? status),
                cell(row.band_label_snapshot),
                cell(row.checked_in_at)
            );
            rowsBody.append(tableRow);
        });
        applyFilter(selectedFilter);
    };

    const renderSummary = (summary) => {
        const hasDay = summary !== null;
        dashboardContent.hidden = !hasDay;
        dashboardEmpty.hidden = hasDay;
        if (!hasDay) {
            return;
        }

        dashboardRoot.querySelector("[data-dashboard-date]").textContent = summary.attendance_date;
        dashboardRoot.querySelector("[data-dashboard-description]").textContent =
            `정책 ${summary.policy_name} · 상태 ${summary.status}`;
        dashboardRoot.querySelectorAll("[data-count]").forEach((element) => {
            element.textContent = `${summary[element.dataset.count] ?? 0}명`;
        });

        let dayDetail = dashboardRoot.querySelector("[data-day-detail]");
        if (!dayDetail) {
            dayDetail = document.createElement("a");
            dayDetail.dataset.dayDetail = "";
            dayDetail.textContent = "출석 상세 관리";
            dashboardRoot.querySelector("[data-filter-title]").parentElement.append(dayDetail);
        }
        dayDetail.href = `${dashboardRoot.dataset.dayBase}/${summary.attendance_day_id}`;
    };

    const refreshDashboard = async () => {
        if (document.hidden || pollingPaused) {
            return;
        }
        const liveUpdated = dashboardRoot.querySelector("[data-live-updated]");
        try {
            const response = await fetch(dashboardRoot.dataset.endpoint, {
                credentials: "same-origin",
                cache: "no-store",
                headers: {Accept: "application/json"}
            });
            if (!response.ok) {
                throw new Error(`dashboard request failed: ${response.status}`);
            }
            const data = await response.json();
            renderSummary(data.summary);
            renderRows(data.rows);
            if (liveUpdated) {
                liveUpdated.textContent = `${new Date().toLocaleTimeString("ko-KR")} 갱신`;
            }
        } catch (_error) {
            if (liveUpdated) {
                liveUpdated.textContent = "자동 갱신 실패 · 잠시 후 재시도";
            }
        }
    };

    const liveToggle = dashboardRoot.querySelector("[data-live-toggle]");
    liveToggle.addEventListener("click", () => {
        pollingPaused = !pollingPaused;
        liveToggle.textContent = pollingPaused ? "자동 갱신 다시 시작" : "자동 갱신 일시정지";
        if (pollingPaused) {
            dashboardRoot.querySelector("[data-live-updated]").textContent = "자동 갱신 일시정지됨";
        } else {
            refreshDashboard();
        }
    });

    applyFilter(selectedFilter);
    refreshDashboard();
    window.setInterval(refreshDashboard, 5000);
    document.addEventListener("visibilitychange", refreshDashboard);
}
