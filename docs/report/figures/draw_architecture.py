"""시스템 구성도 (Ⅱ. 계획서 1절) — matplotlib 렌더링. 실행: python docs/report/figures/draw_architecture.py

상자 채움색 = 역할(AI 모델 / 검증·마스킹 게이트 / 로컬 저장), 테두리 = 개발 주체:
  own    — 직접 설계·구현한 코드 (굵은 실선, '자체 개발')
  hybrid — 외부 모델·플랫폼 위에 자체 통합 코드를 얹은 것 (굵은 점선)
  ext    — 그대로 가져다 쓴 외부 서비스·모델 (얇은 회색 실선)
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
from matplotlib.lines import Line2D

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

OUT = Path(__file__).parent / "fig_architecture.png"

fig, ax = plt.subplots(figsize=(15, 12), dpi=200)
ax.set_xlim(0, 150)
ax.set_ylim(0, 121)
ax.axis("off")

C_CLOUD = "#EAF2FB"
C_DEVICE = "#F3F7F0"
C_EXT = "#FBF1E6"
C_BOX = "white"
C_AI = "#DCE9F7"
C_GATE = "#FDE2E2"
C_DB = "#FFF4D6"
GREY = "#6B7B8C"

KIND_STYLE = {
    "own":    dict(ec="#1F4E79", lw=2.0, ls="-",         tag="자체 개발"),
    "hybrid": dict(ec="#1F4E79", lw=2.0, ls=(0, (4, 2)), tag="외부 모델·플랫폼 + 자체 통합"),
    "ext":    dict(ec="#8A9AA9", lw=1.0, ls="-",         tag="외부 서비스(사용)"),
}


def zone(x, y, w, h, title, color):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.5",
                                fc=color, ec="#9AA5B1", lw=1.2, ls="--"))
    ax.text(x + 1.2, y + h - 1.6, title, fontsize=11.5, fontweight="bold", color="#3A4A5C", va="top")


def box(x, y, w, h, text, fc=C_BOX, fs=8.6, bold_first=True, kind="own"):
    st = KIND_STYLE[kind]
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.3,rounding_size=1",
                                fc=fc, ec=st["ec"], lw=st["lw"], ls=st["ls"]))
    ax.text(x + w - 0.7, y + h - 0.45, st["tag"], fontsize=5.6, ha="right", va="top",
            color=st["ec"], style="italic")
    lines = text.split("\n")
    if bold_first and len(lines) > 1:
        ax.text(x + w / 2, y + h - 1.9, lines[0], ha="center", va="top", fontsize=fs + 0.6, fontweight="bold")
        ax.text(x + w / 2, y + h - 1.9 - (fs + 0.6) * 0.42, "\n".join(lines[1:]), ha="center", va="top",
                fontsize=fs - 0.6, linespacing=1.25)
    else:
        ax.text(x + w / 2, y + h / 2 - 0.3, text, ha="center", va="center", fontsize=fs, fontweight="bold")


def arrow(p1, p2, label="", color="#2F3E4E", style="-|>", lw=1.3, ls="-", lpos=0.5, dx=0, dy=0, fs=7.6):
    ax.add_patch(FancyArrowPatch(p1, p2, arrowstyle=style, mutation_scale=13, color=color, lw=lw, ls=ls))
    if label:
        x = p1[0] + (p2[0] - p1[0]) * lpos + dx
        y = p1[1] + (p2[1] - p1[1]) * lpos + dy
        ax.text(x, y, label, fontsize=fs, ha="center", va="center", color=color,
                bbox=dict(fc="white", ec="none", pad=0.6, alpha=0.9))


def seg(p1, p2, color="#2F3E4E", lw=1.3, ls="-"):
    ax.add_line(Line2D([p1[0], p2[0]], [p1[1], p2[1]], color=color, lw=lw, ls=ls))


# ── 영역 ────────────────────────────────────────────────────────────────────
zone(1, 85, 148, 34, "Cloud Side (Firebase · Google Cloud, asia-northeast3)", C_CLOUD)
zone(1, 2, 105, 79, "Client Side — Android 온디바이스 (Kotlin · Jetpack Compose)", C_DEVICE)
zone(108, 2, 41, 79, "External Tools (APIs) — 프록시 경유만 접근", C_EXT)

# ── 클라우드 ────────────────────────────────────────────────────────────────
box(4, 88, 22, 12, "Firebase Auth\nGoogle 로그인\nUID 발급", kind="ext")
box(29, 88, 24, 12, "Cloud Firestore\nrooms · messages\ninviteCodes · users\n보안 규칙(참여자만)", kind="hybrid")
box(56, 88, 18, 12, "FCM\n푸시 알림", kind="ext")
box(78, 88, 34, 21, "Cloud Functions (2nd gen)\nnotifyNewMessage (Firestore 트리거)\n"
    "geminiGenerate · kakaoSearch · weatherMidFcst\n(callable, 로그인 필수, 모델 allowlist,\n429/503 백오프 재시도)",
    kind="hybrid")
box(78, 110.5, 34, 5.5, "Secret Manager — GEMINI / KAKAO / WEATHER 키 (APK에 없음)", fs=7.8, bold_first=False, kind="ext")
box(116, 88, 30, 14, "Gemini (gemini-3.5-flash)\ngenerateContent REST\nFunction Calling +\nJSON 응답 스키마",
    fc=C_AI, kind="ext")

# ── 디바이스: UI ──────────────────────────────────────────────────────────────
box(4, 54, 24, 15, "UI (Compose)\n채팅 · 이야기 정리 ·\nAI 리포트 카드 · 캘린더 ·\n후기 팝업 · 지난 추천 보기", kind="own")

# Track 1
ax.text(4, 51.5, "Track 1 — 백그라운드 상태 압축 (메시지 10개마다)", fontsize=9, fontweight="bold", color="#2E6B3F")
box(4, 27, 24, 15, "Gemma 4 E2B (LiteRT-LM, GPU)\ncompress()\nconstrained decoding\n(record_status 툴 스키마)",
    fc=C_AI, kind="hybrid")
box(4, 8, 24, 14, "StatusCompressionPipeline\n방어적 JSON 파싱 →\n직전 상태와 결정론적 병합", kind="own")
box(33, 8, 20, 14, "Room DB\nuser_status\n(참석자·선호/불호·가능일정)", fc=C_DB, kind="hybrid")

# Track 2
ax.text(34, 73.5, "Track 2 — 추천 하네스 루프 (이야기 정리)", fontsize=9, fontweight="bold", color="#8A3B12")
box(34, 49, 30, 18, "AgentOrchestrator (중앙 통제실)\n프롬프트 조립 · 도구 대리 실행\n재시도 ≤ 3회 · 도구 왕복 ≤ 8회\nAgentEvent 발행",
    fc="#FFF9E6", kind="own")
box(68, 58, 22, 11, "Gemma 4 E2B\nsummarizeForPrivacy()\n익명화 요약", fc=C_AI, kind="hybrid")
box(68, 46, 22, 9, "PiiScrubber\n결정론적 마스킹 게이트\n(이름·전화·이메일·호칭)", fc=C_GATE, kind="own")
box(93, 58, 12, 11, "Embedding\nGemma-300m\n(LiteRT)", fc=C_AI, fs=7.8, kind="hybrid")
box(93, 46, 12, 9, "후기 RAG\ncos top-k", fs=7.8, kind="own")
box(34, 26, 30, 13, "Guardrail (하드 게이트)\n장소 실존 팩트 체크\nOPEN / CLOSED / UNKNOWN\nfail-open 금지", fc=C_GATE, kind="own")
box(68, 26, 22, 13, "Reflection (소프트 게이트)\n사용자 불호(싫어요:)\n위반 자기비평", fc=C_GATE, kind="own")
box(57, 8, 24, 14, "Room DB\nmeeting_summary(JSON) ·\nfeedback(+임베딩) ·\nrecommended_room\n→ 지난 추천 보기",
    fc=C_DB, fs=8.2, kind="hybrid")
box(84, 8, 21, 14, "ModelDownloadService\nGemma 4 · EmbeddingGemma ·\n토크나이저 (HF, 이어받기)", fs=7.8, kind="own")

# ── 외부 API ────────────────────────────────────────────────────────────────
box(112, 46, 34, 12, "카카오 로컬 REST API\n키워드 검색 (searchPlace)\n장소 실존 검증 (Guardrail)", kind="ext")
box(112, 27, 34, 12, "기상청 중기예보 API\nMidFcstInfoService\n육상·기온 예보 (getWeather)", kind="ext")

# ── 화살표 ──────────────────────────────────────────────────────────────────
arrow((14, 69), (14, 88), "로그인", lpos=0.68, dx=-4.5)
arrow((24, 69), (38, 88), "채팅 실시간 구독", lpos=0.68, dx=7)
arrow((62, 88), (28, 69), "푸시", ls=":", color=GREY, lpos=0.3, dx=-3, dy=2)

# Track 1
arrow((16, 54), (16, 42), "① 최근 15개 메시지", lpos=0.5, dx=8)
arrow((16, 27), (16, 22), "② 툴콜 JSON", lpos=0.5, dx=8)
arrow((28, 15), (33, 15), "③ Upsert", dy=2.2)
seg((31, 22), (31, 58))                      # ④ 프로필 로드 — 열 사이 통로로 우회
arrow((31, 58), (34, 58), "")
ax.text(31, 44, "④ 프로필 로드", fontsize=7.2, ha="center", va="center", color="#2F3E4E",
        bbox=dict(fc="white", ec="none", pad=0.5, alpha=0.9))

# Track 2
arrow((28, 64), (34, 64), "⑤ 이야기 정리", dy=1.8, fs=7.2)
arrow((34, 61), (28, 61), "⑮ 카드 표시", dy=-1.8, fs=7.2)
arrow((64, 63.5), (68, 63.5), "⑥ 원문", dy=1.8, fs=7.2)
arrow((79, 58), (79, 55), "")
arrow((68, 50.5), (64, 57), "⑦ 요약(마스킹)", lpos=0.5, dx=-1, dy=-3.2, fs=7.2)
arrow((90, 63.5), (93, 63.5), "")
arrow((99, 58), (99, 55), "")
arrow((93, 50.5), (90, 50.5), "")
ax.text(95, 42.5, "⑧ RAG 후기 → 스크러버 통과", fontsize=7.2, color="#2F3E4E", ha="center")
arrow((49, 67), (95, 88), "⑨ 프롬프트+도구명세 → 프록시", lpos=0.55)
arrow((100, 88), (58, 67), "⑩ 툴콜 / 최종 JSON", ls="--", lpos=0.2, dx=6, dy=-2)
arrow((112, 95), (116, 95), "키 부착", dy=2)
arrow((100, 88), (129, 58), "", ls=":", color=GREY)
arrow((100, 88), (129, 39), "", ls=":", color=GREY)
ax.text(129, 63, "⑪ 도구 대리 실행\n(프록시 경유)", fontsize=7.6, color=GREY, ha="center", va="center",
        bbox=dict(fc="white", ec="none", pad=0.5, alpha=0.9))
arrow((46, 49), (46, 39), "⑫ 추천 초안", lpos=0.5, dx=-5, dy=2, fs=7.2)
arrow((64, 32.5), (68, 32.5), "")
arrow((60, 39), (60, 49), "⑬ 반려 사유 누적 → 재시도", lpos=0.5, dx=6, fs=7.2)
arrow((55, 26), (65, 22), "⑭ 검증 통과 결과 영속", lpos=0.5, fs=7.2)

# ── 범례: 채움색(역할) + 테두리(개발 주체) ─────────────────────────────────────
ax.add_patch(FancyBboxPatch((109, 5), 38, 17, boxstyle="round,pad=0.3", fc="white", ec="#9AA5B1", lw=0.8))
ax.text(111, 20.6, "범례", fontsize=8.6, fontweight="bold")
ax.text(111, 18.3, "채움색 = 역할", fontsize=6.8, color="#3A4A5C")
for i, (c, t) in enumerate([(C_AI, "AI 모델"), (C_GATE, "검증·마스킹 게이트"), (C_DB, "로컬 영구 저장")]):
    yy = 15.6 - i * 3
    ax.add_patch(FancyBboxPatch((111, yy - 0.9), 3, 1.8, boxstyle="square,pad=0", fc=c, ec="#4A5A6C", lw=0.6))
    ax.text(115, yy, t, fontsize=6.8, va="center")
ax.text(128, 18.3, "테두리 = 개발 주체", fontsize=6.8, color="#3A4A5C")
for i, (k, t) in enumerate([("own", "직접 설계·구현"), ("hybrid", "외부 플랫폼 + 자체 통합 코드"),
                            ("ext", "가져다 쓴 외부 서비스·모델")]):
    yy = 15.6 - i * 3
    st = KIND_STYLE[k]
    ax.add_patch(FancyBboxPatch((128, yy - 0.9), 3, 1.8, boxstyle="square,pad=0", fc="white",
                                ec=st["ec"], lw=st["lw"] * 0.8, ls=st["ls"]))
    ax.text(132, yy, t, fontsize=6.4, va="center")
ax.text(4, 6.6, "채팅 원문은 Track 1·2 모두 기기 안에서만 처리 — 클라우드로는 마스킹된 요약문·성향 프로필·후기만 전송",
        fontsize=7.4, color="#8A3B12", va="top")

fig.savefig(OUT, bbox_inches="tight", facecolor="white")
print(f"saved {OUT}")
