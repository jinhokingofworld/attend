(() => {
    const policyForm = document.getElementById("policyForm");
    if (!policyForm) return;

    const repeatEnabledRadios = policyForm.querySelectorAll("input[name='repeatEnabled']");
    const recurrenceRadios = policyForm.querySelectorAll(
        "input[type='radio'][name='recurrencePattern']");
    const recurrenceValue = document.getElementById("recurrenceValue");
    const repeatOptions = document.getElementById("repeatOptions");
    const endDateField = document.getElementById("policyEndDateField");
    const endDateInput = policyForm.querySelector("input[name='endDate']");
    const intervalInput = policyForm.querySelector("input[name='interval']");
    const recurrenceSummary = document.getElementById("recurrenceSummary");
    const intervalHelp = document.getElementById("intervalHelp");
    const recurrenceCopy = {
        NONE: ["선택한 시작일에 한 번만 출석을 받습니다.", ""],
        DAILY: ["매일 반복되는 출석 정책을 만듭니다.", "일 단위로 반복합니다."],
        WEEKLY: ["선택한 요일에 반복되는 출석 정책을 만듭니다.", "주 단위로 반복합니다."],
        MONTHLY: ["선택한 날짜에 반복되는 출석 정책을 만듭니다.", "개월 단위로 반복합니다."]
    };

    function setControlsEnabled(container, enabled) {
        container.querySelectorAll("input, select").forEach(control => {
            control.disabled = !enabled;
        });
    }

    function updateRecurrence() {
        const repeats = policyForm.querySelector("input[name='repeatEnabled']:checked").value === "true";
        const recurrence = repeats
            ? policyForm.querySelector("input[name='recurrencePattern']:checked").value
            : "NONE";
        const [summary, intervalText] = recurrenceCopy[recurrence];
        recurrenceValue.value = recurrence;
        recurrenceRadios.forEach(radio => {
            radio.disabled = !repeats;
        });
        recurrenceSummary.textContent = summary;
        repeatOptions.hidden = !repeats;
        endDateField.hidden = !repeats;
        endDateInput.disabled = !repeats;
        intervalInput.disabled = !repeats;
        intervalHelp.textContent = intervalText;
        policyForm.querySelectorAll("[data-recurrence]").forEach(panel => {
            const selected = repeats && panel.dataset.recurrence === recurrence;
            panel.hidden = !selected;
            setControlsEnabled(panel, selected);
        });
    }

    function updateFinalizationLabels() {
        const rows = [...document.querySelectorAll("#bands .band-row")];
        rows.forEach((row, index) => {
            const isFinal = index === rows.length - 1;
            row.classList.toggle("band-row--final", isFinal);
            row.querySelector(".band-time-label").textContent = isFinal
                ? "마감 시간 (마지막 태깅 허용 시각)"
                : "단계 종료 시간";
            row.querySelector(".remove-band").disabled = index === 0 || rows.length <= 2;
        });
    }

    repeatEnabledRadios.forEach(radio => radio.addEventListener("change", updateRecurrence));
    recurrenceRadios.forEach(radio => radio.addEventListener("change", updateRecurrence));
    document.getElementById("addBand")?.addEventListener("click", () => {
        const row = document.querySelector("#bands .band-row").cloneNode(true);
        row.querySelector("input[name='bandLabel']").value = "";
        row.querySelector("select[name='bandStatus']").value = "LATE";
        row.querySelector("input[name='bandUpperTime']").value = "";
        document.getElementById("bands").appendChild(row);
        updateFinalizationLabels();
    });
    document.getElementById("bands")?.addEventListener("click", event => {
        const removeButton = event.target.closest(".remove-band");
        if (!removeButton || removeButton.disabled) return;
        removeButton.closest(".band-row").remove();
        updateFinalizationLabels();
    });

    updateRecurrence();
    updateFinalizationLabels();
})();
