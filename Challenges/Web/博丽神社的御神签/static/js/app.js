const { supabaseUrl, supabaseKey } = window.__APP_CONFIG__;
const supabaseClient = window.supabase.createClient(supabaseUrl, supabaseKey);

const drawButton = document.getElementById("drawBtn");
const systemStatus = document.getElementById("systemStatus");
const rollOutput = document.getElementById("rollOutput");
const fortuneOutput = document.getElementById("fortuneOutput");
const drawCount = document.getElementById("drawCount");
const actionHint = document.getElementById("actionHint");
const recentDraws = document.getElementById("recentDraws");
const fortuneContainer = document.getElementById("fortuneContainer");
const fortunePlaceholder = document.getElementById("fortunePlaceholder");
const adminEntry = document.querySelector(".admin-entry");
const dieFaces = [
    document.getElementById("dieFaceFirst"),
    document.getElementById("dieFaceSecond"),
    document.getElementById("dieFaceThird")
];

const PIP_MAP = {
    0: [],
    1: [5],
    2: [1, 9],
    3: [1, 5, 9],
    4: [1, 3, 7, 9],
    5: [1, 3, 5, 7, 9],
    6: [1, 3, 4, 6, 7, 9]
};

let sessionDrawCount = 0;
const drawHistory = [];

if (adminEntry && adminEntry.tagName === "BUTTON") {
    adminEntry.addEventListener("click", () => {
        window.location.href = "/admin/login";
    });
}

function rollDie() {
    return Math.floor(Math.random() * 6) + 1;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function formatMultiline(value) {
    return escapeHtml(value).replaceAll("\n", "<br>");
}

function normalizeArray(value) {
    return Array.isArray(value) ? value : [];
}

function flattenNoteBlocks(noteBlocks) {
    return normalizeArray(noteBlocks).reduce((all, block) => {
        return all.concat(normalizeArray(block));
    }, []);
}

function drawByDiceRule() {
    while (true) {
        let first = rollDie();
        while (first > 4) {
            first = rollDie();
        }

        const second = rollDie();
        const third = rollDie();
        const value = first * 36 + second * 6 + third - 42;

        if (value <= 128) {
            return { first, second, third, value };
        }
    }
}

function formatTimestamp() {
    return new Date().toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}

function updateDieFace(face, value) {
    const activePips = new Set(PIP_MAP[value] || []);

    face.dataset.value = String(value);
    face.querySelectorAll(".pip").forEach((pip, index) => {
        pip.classList.toggle("is-active", activePips.has(index + 1));
    });
}

function updateDiceDisplay(draw) {
    const values = draw ? [draw.first, draw.second, draw.third] : [0, 0, 0];
    values.forEach((value, index) => {
        updateDieFace(dieFaces[index], value);
    });
}

function setStatus(status, roll = "-", fortune = "-") {
    systemStatus.textContent = status;
    rollOutput.textContent = roll;
    fortuneOutput.textContent = fortune;
}

function setHint(message) {
    actionHint.textContent = message;
}

function setDrawCount() {
    drawCount.textContent = `${sessionDrawCount} 次`;
}

function renderStateCard(code, title, text) {
    return `
        <article class="fortune-slip fortune-slip--state" aria-label="神签状态">
            <div class="slip-accent" aria-hidden="true"></div>
            <div class="slip-state">
                <p class="slip-state-code">${escapeHtml(code)}</p>
                <p class="slip-state-title">${escapeHtml(title)}</p>
                <p class="slip-state-text">${escapeHtml(text)}</p>
            </div>
            <p class="slip-footer slip-footer--center">Hakurei Shrine Oracle</p>
        </article>
    `;
}

function renderNoteItems(noteBlocks) {
    return flattenNoteBlocks(noteBlocks)
        .map((item) => `
            <div class="note-item">
                <span class="note-label">${escapeHtml(item.label)}</span>
                <span class="note-text">${formatMultiline(item.text)}</span>
            </div>
        `)
        .join("");
}

function renderFortune(entry) {
    return `
        <article class="fortune-slip" aria-label="抽出的神签">
            <div class="slip-accent" aria-hidden="true"></div>
            <header class="slip-head">
                <div>
                    <p class="slip-number">Fortune No. ${escapeHtml(String(entry.fortune_number).padStart(3, "0"))}</p>
                    <h3 class="slip-fortune">${escapeHtml(entry.fortune_type)}</h3>
                </div>
                <div class="slip-seal" aria-hidden="true">
                    <span>博丽</span>
                    <span>御签</span>
                </div>
            </header>

            <div class="slip-scroll">
                <section class="slip-subject">
                    <p class="slip-name">${formatMultiline(entry.character_name)}</p>
                    <p class="slip-subtitle">${formatMultiline(entry.subtitle)}</p>
                    <p class="slip-ability">${formatMultiline(entry.ability)}</p>
                </section>

                <section class="slip-notes">
                    <p class="slip-label">批注</p>
                    <div class="note-grid">
                        ${renderNoteItems(entry.note_blocks)}
                    </div>
                </section>
            </div>

            <footer class="slip-footer">
                <span>Cloud-delivered omikuji</span>
                <span>Hakurei Shrine</span>
            </footer>
        </article>
    `;
}

function renderHistory() {
    if (!drawHistory.length) {
        recentDraws.innerHTML = `<li class="history-empty">暂时还没有新的求签记录。</li>`;
        return;
    }

    recentDraws.innerHTML = drawHistory
        .map((entry) => `
            <li class="history-item">
                <span class="history-time">${escapeHtml(entry.time)}</span>
                <span class="history-result">${escapeHtml(entry.result)}</span>
                <span class="history-meta">${escapeHtml(entry.meta)}</span>
            </li>
        `)
        .join("");
}

function pushHistory(result, meta) {
    drawHistory.unshift({
        time: formatTimestamp(),
        result,
        meta
    });

    if (drawHistory.length > 4) {
        drawHistory.length = 4;
    }

    renderHistory();
}

async function loadFortune() {
    sessionDrawCount += 1;
    setDrawCount();

    const draw = drawByDiceRule();
    const rollPath = `${draw.first}-${draw.second}-${draw.third}`;

    updateDiceDisplay(draw);
    setStatus("查询中", rollPath, `#${draw.value}`);
    setHint(`第 ${draw.value} 号签已定位，正在向河童数据库请求签文。`);

    drawButton.disabled = true;
    drawButton.textContent = "签文显现中...";
    fortunePlaceholder.classList.add("is-hidden");
    fortuneContainer.classList.remove("is-hidden");
    fortuneContainer.innerHTML = renderStateCard(
        "Fetching",
        "签文正在显形",
        `系统正在读取第 ${draw.value} 号神签，请稍候片刻。`
    );

    const { data, error } = await supabaseClient
        .from("omikuji_entries")
        .select("fortune_number, character_name, fortune_type, subtitle, ability, poem_lines, note_blocks")
        .eq("fortune_number", draw.value)
        .single();

    if (error) {
        setStatus("读取失败", rollPath, `#${draw.value}`);
        setHint("云端签库返回错误。前端已保留失败信息，方便继续观察系统异常。");
        pushHistory(`第 ${draw.value} 号签读取失败`, `骰路 ${rollPath}`);
        fortuneContainer.innerHTML = renderStateCard(
            "Read Error",
            "签文调取失败",
            error.message || JSON.stringify(error)
        );
        return;
    }

    setStatus("已送达", rollPath, `#${draw.value}`);
    setHint(`本次求签结果为“${data.fortune_type}”，签文已经送达展示区。`);
    pushHistory(`第 ${draw.value} 号签 · ${data.fortune_type}`, `骰路 ${rollPath}`);
    fortuneContainer.innerHTML = renderFortune(data);
}

drawButton.addEventListener("click", () => {
    loadFortune()
        .catch((error) => {
            setStatus("脚本错误");
            setHint("前端脚本在请求过程中出现异常，错误信息已经输出到结果区。");
            fortunePlaceholder.classList.add("is-hidden");
            fortuneContainer.classList.remove("is-hidden");
            fortuneContainer.innerHTML = renderStateCard(
                "Script Error",
                "前端脚本发生异常",
                error.message || String(error)
            );
        })
        .finally(() => {
            drawButton.disabled = false;
            drawButton.textContent = "再次摇签";
        });
});

updateDiceDisplay(null);
setDrawCount();
renderHistory();
