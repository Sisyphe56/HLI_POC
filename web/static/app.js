// State
let sessionId = null;
let stepResults = {};
let currentTab = null;

// DOM refs
const fileInput = document.getElementById("file-input");
const uploadArea = document.getElementById("upload-area");
const fileInfo = document.getElementById("file-info");
const fileName = document.getElementById("file-name");
const resetBtn = document.getElementById("reset-btn");
const stepperSection = document.getElementById("stepper-section");
const substepSection = document.getElementById("substep-section");
const logSection = document.getElementById("log-section");
const logArea = document.getElementById("log-area");
const resultsSection = document.getElementById("results-section");
const resultTabs = document.getElementById("result-tabs");
const resultContent = document.getElementById("result-content");

// Upload handlers
uploadArea.addEventListener("click", () => fileInput.click());
uploadArea.addEventListener("dragover", (e) => {
    e.preventDefault();
    uploadArea.classList.add("dragover");
});
uploadArea.addEventListener("dragleave", () => {
    uploadArea.classList.remove("dragover");
});
uploadArea.addEventListener("drop", (e) => {
    e.preventDefault();
    uploadArea.classList.remove("dragover");
    const files = e.dataTransfer.files;
    if (files.length > 0) handleFile(files[0]);
});
fileInput.addEventListener("change", () => {
    if (fileInput.files.length > 0) handleFile(fileInput.files[0]);
});
resetBtn.addEventListener("click", () => {
    sessionId = null;
    stepResults = {};
    currentTab = null;
    fileInput.value = "";
    uploadArea.style.display = "";
    fileInfo.style.display = "none";
    stepperSection.style.display = "none";
    substepSection.style.display = "none";
    logSection.style.display = "none";
    resultsSection.style.display = "none";
    logArea.innerHTML = "";
    resultTabs.innerHTML = "";
    resultContent.innerHTML = "";
    resetStepUI();
});

async function handleFile(file) {
    if (!file.name.toLowerCase().endsWith(".pdf")) {
        alert("PDF 파일만 업로드 가능합니다.");
        return;
    }

    const formData = new FormData();
    formData.append("file", file);

    try {
        const resp = await fetch("/api/upload", { method: "POST", body: formData });
        if (!resp.ok) {
            const err = await resp.json();
            alert(err.detail || "업로드 실패");
            return;
        }
        const data = await resp.json();
        sessionId = data.session_id;

        uploadArea.style.display = "none";
        fileInfo.style.display = "flex";
        fileName.textContent = data.filename;
        stepperSection.style.display = "";
        logSection.style.display = "";

        resetStepUI();
        document.getElementById("btn-step-1").disabled = false;
        addLog("파일 업로드 완료: " + data.filename);
    } catch (e) {
        alert("업로드 오류: " + e.message);
    }
}

function resetStepUI() {
    for (let i = 1; i <= 4; i++) {
        const el = document.getElementById(`step-${i}`);
        el.className = "step";
        const btn = document.getElementById(`btn-step-${i}`);
        btn.disabled = true;
        btn.textContent = "실행";
        btn.className = "btn btn-run";
    }
    // Reset substeps
    const subs = ["payment_cycle", "annuity_age", "insurance_period", "join_age"];
    subs.forEach((s) => {
        const el = document.getElementById(`sub-${s}`);
        if (el) {
            el.className = "substep";
            el.querySelector(".substep-icon").innerHTML = "&#9675;";
        }
        const st = document.getElementById(`sub-status-${s}`);
        if (st) st.textContent = "대기";
    });
}

// Step button handlers
for (let i = 1; i <= 4; i++) {
    document.getElementById(`btn-step-${i}`).addEventListener("click", () => runStep(i));
}

async function runStep(step) {
    if (!sessionId) return;

    const btn = document.getElementById(`btn-step-${step}`);
    btn.disabled = true;
    btn.textContent = "실행 중...";
    btn.className = "btn btn-run running";

    const stepEl = document.getElementById(`step-${step}`);
    stepEl.className = "step running";

    if (step === 2) {
        substepSection.style.display = "";
    }

    addLog(`Step ${step} 실행 시작...`);

    const eventSource = new EventSource(
        `/api/pipeline/${sessionId}/run?step=${step}`
    );

    eventSource.addEventListener("status", (e) => {
        const data = JSON.parse(e.data);
        addLog(data.message);

        if (data.substep) {
            updateSubstep(data.substep, data.status, data.message);
        }
    });

    eventSource.addEventListener("result", (e) => {
        const data = JSON.parse(e.data);

        if (data.substep) {
            updateSubstep(data.substep, data.status, data.message);
            if (data.data) {
                stepResults[`step2_${data.substep}`] = data.data;
                updateResultTabs();
            }
        } else if (data.step === 1 && data.data) {
            stepResults.step1 = data.data;
            updateResultTabs();
        } else if (data.step === 3 && data.data) {
            stepResults.step3 = data.data;
            updateResultTabs();
        } else if (data.step === 4 && data.data) {
            stepResults.step4 = data.data;
            updateResultTabs();
        }

        if (data.message) {
            addLog(data.message, data.status === "error" ? "error" : "success");
        }
    });

    eventSource.addEventListener("error", (e) => {
        if (e.data) {
            const data = JSON.parse(e.data);
            addLog(data.message, "error");
            if (data.substep) {
                updateSubstep(data.substep, "error", data.message);
            }
        }
    });

    eventSource.addEventListener("done", (e) => {
        eventSource.close();
        const data = JSON.parse(e.data);

        stepEl.className = "step completed";
        btn.textContent = "완료";
        btn.className = "btn btn-run";
        btn.disabled = true;

        // Check for errors in substeps
        if (step === 2) {
            const subs = ["payment_cycle", "annuity_age", "insurance_period", "join_age"];
            const hasError = subs.some((s) => {
                const el = document.getElementById(`sub-${s}`);
                return el && el.classList.contains("error");
            });
            if (hasError) {
                stepEl.className = "step completed"; // Still allow proceeding
            }
        }

        // Enable next step
        if (step < 4) {
            const nextBtn = document.getElementById(`btn-step-${step + 1}`);
            nextBtn.disabled = false;
        }

        addLog(`Step ${step} 완료`, "success");
    });

    eventSource.onerror = () => {
        eventSource.close();
        stepEl.className = "step error";
        btn.textContent = "오류";
        btn.className = "btn btn-run";
        addLog(`Step ${step} 연결 오류`, "error");
    };
}

function updateSubstep(name, status, message) {
    const el = document.getElementById(`sub-${name}`);
    if (!el) return;

    el.className = `substep ${status}`;
    const icon = el.querySelector(".substep-icon");
    if (status === "running") icon.innerHTML = "&#9676;";
    else if (status === "completed") icon.innerHTML = "&#9745;";
    else if (status === "error") icon.innerHTML = "&#10005;";

    const statusEl = document.getElementById(`sub-status-${name}`);
    if (statusEl) {
        if (status === "running") statusEl.textContent = "진행 중...";
        else if (status === "completed") {
            const match = message.match(/\((\d+)건\)/);
            statusEl.textContent = match ? `완료 (${match[1]}건)` : "완료";
        } else if (status === "error") statusEl.textContent = "오류";
    }
}

function addLog(message, type = "") {
    const entry = document.createElement("div");
    entry.className = `log-entry ${type}`;
    const now = new Date();
    const time = `${now.getHours().toString().padStart(2, "0")}:${now.getMinutes().toString().padStart(2, "0")}:${now.getSeconds().toString().padStart(2, "0")}`;
    entry.innerHTML = `<span class="log-time">[${time}]</span> ${escapeHtml(message)}`;
    logArea.appendChild(entry);
    logArea.scrollTop = logArea.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement("div");
    div.textContent = text;
    return div.innerHTML;
}

// Result tabs
const TAB_CONFIG = {
    step1: { label: "상품분류", step: 1 },
    step2_payment_cycle: { label: "납입주기", step: 2 },
    step2_annuity_age: { label: "보기개시나이", step: 2 },
    step2_insurance_period: { label: "가입가능보기납기", step: 2 },
    step2_join_age: { label: "가입가능나이", step: 2 },
    step3: { label: "코드매핑", step: 3 },
    step4: { label: "정답 검증", step: 4 },
};

function updateResultTabs() {
    resultsSection.style.display = "";
    resultTabs.innerHTML = "";

    const availableKeys = Object.keys(TAB_CONFIG).filter((k) => stepResults[k]);
    if (availableKeys.length === 0) {
        resultsSection.style.display = "none";
        return;
    }

    availableKeys.forEach((key) => {
        const btn = document.createElement("button");
        btn.className = `tab ${currentTab === key ? "active" : ""}`;
        btn.textContent = TAB_CONFIG[key].label;
        btn.addEventListener("click", () => {
            currentTab = key;
            updateResultTabs();
        });
        resultTabs.appendChild(btn);
    });

    if (!currentTab || !stepResults[currentTab]) {
        currentTab = availableKeys[availableKeys.length - 1];
        // Update active class
        resultTabs.querySelectorAll(".tab").forEach((t, i) => {
            t.className = `tab ${availableKeys[i] === currentTab ? "active" : ""}`;
        });
    }

    renderResult(currentTab);
}

function renderResult(key) {
    const data = stepResults[key];
    if (!data) {
        resultContent.innerHTML = '<div class="empty-state">데이터 없음</div>';
        return;
    }

    if (key === "step1") {
        renderTable(data, ["상품명칭", "세부종목1", "세부종목2", "세부종목3", "세부종목4", "상품명"]);
    } else if (key.startsWith("step2_")) {
        renderStep2Result(key, data);
    } else if (key === "step3") {
        renderStep3Result(data);
    } else if (key === "step4") {
        renderStep4Result(data);
    }
}

function renderStep2Result(key, data) {
    if (key === "step2_payment_cycle") {
        renderTable(data, ["상품명칭", "상품명", "납입주기"]);
    } else if (key === "step2_annuity_age") {
        renderTable(data, ["상품명칭", "상품명", "보기개시나이정보"]);
    } else if (key === "step2_insurance_period") {
        renderTable(data, ["상품명칭", "상품명", "가입가능보기납기"]);
    } else if (key === "step2_join_age") {
        renderTable(data, ["상품명칭", "상품명", "가입가능나이"]);
    }
}

function renderStep3Result(data) {
    // Show per-dataset stats summary
    let html = '<div class="result-summary">';
    for (const [ds, info] of Object.entries(data)) {
        const stats = info.stats || {};
        const label = {
            product_classification: "상품분류",
            payment_cycle: "납입주기",
            annuity_age: "보기개시나이",
            insurance_period: "가입가능보기납기",
            join_age: "가입가능나이",
        }[ds] || ds;

        html += `
            <div class="summary-card">
                <div class="label">${label}</div>
                <div class="value">${stats.matched || 0} / ${stats.total || 0}</div>
            </div>
        `;
    }
    html += "</div>";

    // Show first dataset's mapped rows as table
    const firstDs = Object.keys(data)[0];
    if (firstDs && data[firstDs].mapped_rows) {
        const rows = data[firstDs].mapped_rows;
        html += buildTableHtml(rows, [
            "isrn_kind_dtcd", "isrn_kind_itcd", "isrn_kind_sale_nm",
            "prod_dtcd", "prod_itcd", "상품명",
        ]);
    }

    resultContent.innerHTML = html;
}

function renderStep4Result(data) {
    let html = '<div class="result-summary">';
    for (const [ds, info] of Object.entries(data)) {
        const summary = info.summary || {};
        const label = {
            payment_cycle: "납입주기",
            annuity_age: "보기개시나이",
            insurance_period: "가입가능보기납기",
            join_age: "가입가능나이",
        }[ds] || ds;

        if (info.error) {
            html += `
                <div class="summary-card">
                    <div class="label">${label}</div>
                    <div class="value" style="color:#e74c3c">오류</div>
                </div>
            `;
        } else {
            html += `
                <div class="summary-card match">
                    <div class="label">${label}</div>
                    <div class="value">${summary.match_rate || "N/A"}</div>
                    <div class="label">${summary.matched || 0} / ${summary.total || 0}</div>
                </div>
            `;
        }
    }
    html += "</div>";

    // Show details table for first dataset
    const firstDs = Object.keys(data).find((k) => data[k].details && data[k].details.length > 0);
    if (firstDs) {
        const details = data[firstDs].details;
        html += '<div class="result-table-wrap"><table class="result-table"><thead><tr>';
        html += "<th>상품명</th><th>보종DTCD</th><th>보종ITCD</th><th>결과</th><th>사유</th>";
        html += "</tr></thead><tbody>";
        details.forEach((d) => {
            const badge = d.matched
                ? '<span class="match-badge matched">일치</span>'
                : '<span class="match-badge unmatched">불일치</span>';
            html += `<tr>
                <td title="${escapeHtml(d.product || "")}">${escapeHtml(d.product || "")}</td>
                <td>${escapeHtml(d.isrn_kind_dtcd || "")}</td>
                <td>${escapeHtml(d.isrn_kind_itcd || "")}</td>
                <td>${badge}</td>
                <td>${escapeHtml(d.reason || "")}</td>
            </tr>`;
        });
        html += "</tbody></table></div>";
    }

    resultContent.innerHTML = html;
}

function renderTable(data, columns) {
    if (!data || data.length === 0) {
        resultContent.innerHTML = '<div class="empty-state">데이터 없음</div>';
        return;
    }

    // Auto-detect columns if not specified
    if (!columns || columns.length === 0) {
        columns = Object.keys(data[0]);
    }

    // Filter to existing columns
    const existingCols = columns.filter((c) =>
        data.some((row) => row[c] !== undefined && row[c] !== null)
    );
    if (existingCols.length === 0) {
        existingCols.push(...Object.keys(data[0]).slice(0, 8));
    }

    resultContent.innerHTML = buildTableHtml(data, existingCols);
}

function buildTableHtml(data, columns) {
    let html = `<div class="result-summary">
        <div class="summary-card">
            <div class="label">전체 건수</div>
            <div class="value">${data.length}</div>
        </div>
    </div>`;

    html += '<div class="result-table-wrap"><table class="result-table"><thead><tr>';
    columns.forEach((col) => {
        html += `<th>${escapeHtml(col)}</th>`;
    });
    html += "</tr></thead><tbody>";

    data.forEach((row) => {
        html += "<tr>";
        columns.forEach((col) => {
            let val = row[col];
            if (val === null || val === undefined) val = "";
            if (typeof val === "object") val = JSON.stringify(val, null, 0);
            html += `<td title="${escapeHtml(String(val))}">${escapeHtml(String(val))}</td>`;
        });
        html += "</tr>";
    });

    html += "</tbody></table></div>";
    return html;
}
