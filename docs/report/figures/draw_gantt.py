"""개발 일정 간트차트 (Ⅱ. 계획서 7절) — 2026-03 ~ 2026-11. 실행: python docs/report/figures/draw_gantt.py
실적(3~9월)은 git 이력·DEVLOG 기준, 계획(9~11월)은 출시 준비·문서 일정."""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

OUT = Path(__file__).parent / "fig_gantt.png"

# (작업명, 담당, 시작월(소수=월중), 끝월, 구분) — 월은 3.0=3월초, 11.99=11월말
tasks = [
    ("주제 선정·요구 분석·제안서", "전원", 3.0, 4.5, "done"),
    ("아키텍처 설계(하이브리드 LLM·하네스)", "PM/AI", 4.0, 5.5, "done"),
    ("Android 앱 골격·채팅 UI(Compose)", "FE", 5.0, 6.3, "done"),
    ("Firebase 로그인·Firestore 채팅·초대코드", "BE", 5.5, 6.9, "done"),
    ("Gemma 4 온디바이스 압축·Room 프로필", "AI", 5.5, 6.6, "done"),
    ("Gemini Function Calling·오케스트레이터", "AI", 6.3, 6.9, "done"),
    ("프라이버시 방화벽(익명화 요약·PII 스크러버)", "AI", 6.6, 7.5, "done"),
    ("FCM 푸시·안읽음·AI 리포트 카드·캘린더", "FE/BE", 6.7, 7.0, "done"),
    ("중간 보고서(제안·계획·분석·요구사항)", "전원", 5.8, 6.9, "done"),
    ("디자인 시스템 마이그레이션·치명 버그 수정", "FE", 7.1, 7.3, "done"),
    ("EmbeddingGemma 온디바이스 RAG(스파이크→구현)", "AI", 7.3, 7.5, "done"),
    ("Guardrail 3-상태·Reflection·재시도 상한", "AI", 7.3, 7.5, "done"),
    ("Firestore 보안 규칙·R8 난독화·constrained decoding", "AI/BE", 7.3, 7.5, "done"),
    ("API 키 서버 격리(Functions 프록시)", "BE", 9.1, 9.3, "done"),
    ("출시 준비: 서명·규칙 배포·개인정보처리방침·최소사양", "BE/PM", 9.2, 10.3, "plan"),
    ("Play 내부 테스트 트랙·실기기 검증·버그 수정", "전원", 10.0, 11.0, "plan"),
    ("기말 보고서 현행화(제안·계획·분석·요구사항)", "전원", 9.2, 11.5, "plan"),
    ("최종 발표 자료·시연 준비", "전원", 11.0, 11.9, "plan"),
]

fig, ax = plt.subplots(figsize=(15, 8.2), dpi=200)
colors = {"done": "#4C78A8", "plan": "#F2B134"}
for i, (name, owner, s, e, kind) in enumerate(tasks):
    y = len(tasks) - i
    ax.barh(y, e - s, left=s, height=0.62, color=colors[kind], edgecolor="#333", lw=0.4)
    ax.text(2.92, y, f"{name}", ha="right", va="center", fontsize=9.2)
    ax.text(e + 0.06, y, owner, ha="left", va="center", fontsize=8, color="#444")

ax.set_xlim(3, 12)
ax.set_ylim(0.3, len(tasks) + 0.8)
ax.set_yticks([])
ax.set_xticks(range(3, 12))
ax.set_xticklabels([f"{m}월" for m in range(3, 12)], fontsize=10)
ax.xaxis.set_ticks_position("top")
for m in range(3, 12):
    ax.axvline(m, color="#D0D5DB", lw=0.7, zorder=0)
ax.axvline(9.15, color="#C0392B", lw=1.2, ls="--")
ax.text(9.17, len(tasks) + 0.55, "현재(2026-09-05)", color="#C0392B", fontsize=8.5, va="center")
for s in ["left", "right", "bottom"]:
    ax.spines[s].set_visible(False)
ax.legend(handles=[Patch(color=colors["done"], label="실적(완료)"), Patch(color=colors["plan"], label="계획")],
          loc="upper right", fontsize=9, frameon=False)
ax.set_title("2026 캡스톤 디자인 개발 일정 (2026-03-02 ~ 2026-11-30)", fontsize=12, pad=28, fontweight="bold")
fig.tight_layout()
fig.savefig(OUT, bbox_inches="tight", facecolor="white")
print(f"saved {OUT}")
