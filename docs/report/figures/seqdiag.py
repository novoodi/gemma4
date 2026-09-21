"""
초소형 시퀀스 다이어그램 렌더러 (matplotlib). Graphviz·mermaid CLI 없이 PNG를 만들기 위한 용도.

사용:
    d = Seq("로그인", ["사용자", "시스템", "Firebase Auth"])
    d.msg("사용자", "시스템", "로그인 버튼 클릭")
    d.ret("시스템", "사용자", "메인 화면 이동")      # 점선(응답)
    d.frame("alt", "[문서 있음]"); ...; d.else_("[문서 없음]"); ...; d.end()
    d.note("시스템", "메모")
    d.render("seq_01.png")
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, Rectangle

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

COL_LINE = "#2F3E4E"
COL_RET = "#5B6B7C"
COL_FRAME = "#7A8794"
COL_HEAD = "#EAF2FB"
COL_NOTE = "#FFF9DB"

X_STEP = 3.4          # 참여자 간격
ROW = 1.0             # 메시지 한 줄 높이
HEAD_H = 0.95


class Seq:
    def __init__(self, title, participants, x_step=X_STEP):
        self.title = title
        self.parts = participants
        self.x_step = x_step
        self.items = []
        self._n = 0

    # ── DSL ────────────────────────────────────────────────────────────────
    def msg(self, a, b, text, style="solid", number=True):
        self.items.append(("msg", a, b, text, style, number))

    def ret(self, a, b, text, number=True):
        self.items.append(("msg", a, b, text, "dashed", number))

    def self_msg(self, a, text, number=True):
        self.items.append(("self", a, text, number))

    def frame(self, kind, label=""):
        self.items.append(("frame", kind, label))

    def else_(self, label):
        self.items.append(("else", label))

    def end(self):
        self.items.append(("end",))

    def note(self, a, text, side="right"):
        self.items.append(("note", a, text, side))

    def gap(self, rows=0.5):
        self.items.append(("gap", rows))

    # ── 렌더 ───────────────────────────────────────────────────────────────
    def _x(self, name):
        return self.parts.index(name) * self.x_step

    def render(self, path, dpi=200):
        # 1) 높이 계산 — 각 아이템의 row 소비량
        cost = {"msg": 1.0, "self": 1.15, "frame": 1.1, "else": 0.9, "end": 0.35, "note": 1.0, "gap": None}
        total = 0.0
        for it in self.items:
            total += it[1] if it[0] == "gap" else cost[it[0]]
        width_units = (len(self.parts) - 1) * self.x_step + 3.6
        height_units = total * ROW + HEAD_H + 2.2

        fig_w = max(7.5, width_units * 0.62)
        fig_h = max(4.0, height_units * 0.55)
        fig, ax = plt.subplots(figsize=(fig_w, fig_h), dpi=dpi)
        ax.set_xlim(-1.8, (len(self.parts) - 1) * self.x_step + 1.8)
        ax.set_ylim(-(total * ROW) - 1.2, HEAD_H + 1.1)
        ax.axis("off")

        # 제목
        ax.text(-1.6, HEAD_H + 0.75, f"sd {self.title}", fontsize=10, fontweight="bold", va="center",
                bbox=dict(fc="white", ec=COL_FRAME, lw=0.9, boxstyle="round,pad=0.25"))

        # 2) 참여자 머리상자 + 생명선
        y_bottom = -(total * ROW) - 0.6
        for i, p in enumerate(self.parts):
            x = i * self.x_step
            ax.add_patch(FancyBboxPatch((x - 1.35, 0.05), 2.7, HEAD_H - 0.1, boxstyle="round,pad=0.05,rounding_size=0.15",
                                        fc=COL_HEAD, ec=COL_LINE, lw=1.0))
            ax.text(x, HEAD_H / 2, p, ha="center", va="center", fontsize=8.6, fontweight="bold")
            ax.plot([x, x], [0, y_bottom], color=COL_LINE, lw=0.8, ls=(0, (4, 3)), zorder=0)

        # 3) 아이템 순회
        y = -0.65
        frames = []          # (kind, label, y_top, sections=[(label, y)])
        num = 0
        x_left = -1.55
        x_right = (len(self.parts) - 1) * self.x_step + 1.55

        for it in self.items:
            kind = it[0]
            if kind == "gap":
                y -= it[1] * ROW
                continue
            if kind == "frame":
                y -= 0.15
                frames.append([it[1], it[2], y, []])
                y -= cost["frame"] * ROW - 0.15   # 제목 줄 아래로 첫 메시지 여백 확보
                continue
            if kind == "else":
                frames[-1][3].append((it[1], y + 0.3))
                y -= cost["else"] * ROW
                continue
            if kind == "end":
                fk, fl, y_top, sections = frames.pop()
                depth = len(frames)
                inset = 0.18 * depth
                y_bot = y + 0.05
                rect = Rectangle((x_left + inset, y_bot), (x_right - x_left) - 2 * inset, y_top - y_bot,
                                 fc="none", ec=COL_FRAME, lw=1.0, zorder=1)
                ax.add_patch(rect)
                # 탭 라벨
                ax.text(x_left + inset + 0.08, y_top - 0.08, fk, fontsize=7.6, fontweight="bold", va="top", ha="left",
                        bbox=dict(fc="white", ec=COL_FRAME, lw=0.8, boxstyle="square,pad=0.15"), zorder=3)
                if fl:
                    ax.text(x_left + inset + 1.05, y_top - 0.14, fl, fontsize=7.4, va="top", ha="left", color=COL_LINE,
                            zorder=3)
                for sl, sy in sections:
                    ax.plot([x_left + inset, x_right - inset], [sy, sy], color=COL_FRAME, lw=0.9, ls=(0, (5, 3)))
                    ax.text(x_left + inset + 0.3, sy - 0.12, sl, fontsize=7.4, va="top", ha="left", color=COL_LINE)
                y -= cost["end"] * ROW
                continue
            if kind == "note":
                _, a, text, side = it
                x = self._x(a)
                w = 3.0
                xx = x + 0.35 if side == "right" else x - 0.35 - w
                ax.add_patch(FancyBboxPatch((xx, y - 0.62), w, 0.74, boxstyle="round,pad=0.04", fc=COL_NOTE,
                                            ec="#B8A64A", lw=0.8, zorder=2))
                ax.text(xx + w / 2, y - 0.25, text, fontsize=6.9, ha="center", va="center", zorder=3)
                y -= cost["note"] * ROW
                continue
            if kind == "self":
                _, a, text, number = it
                x = self._x(a)
                num += 1 if number else 0
                label = f"{num} : {text}" if number else text
                ax.plot([x, x + 0.9, x + 0.9], [y, y, y - 0.45], color=COL_LINE, lw=1.0)
                ax.add_patch(FancyArrowPatch((x + 0.9, y - 0.45), (x + 0.02, y - 0.45), arrowstyle="-|>",
                                             mutation_scale=10, color=COL_LINE, lw=1.0))
                ax.text(x + 1.05, y - 0.2, label, fontsize=7.4, ha="left", va="center")
                y -= cost["self"] * ROW
                continue
            if kind == "msg":
                _, a, b, text, style, number = it
                xa, xb = self._x(a), self._x(b)
                num += 1 if number else 0
                label = f"{num} : {text}" if number else text
                dashed = style == "dashed"
                color = COL_RET if dashed else COL_LINE
                ax.add_patch(FancyArrowPatch((xa, y), (xb, y), arrowstyle="-|>", mutation_scale=11, color=color,
                                             lw=1.0, ls=(0, (4, 3)) if dashed else "-", zorder=2))
                ax.text((xa + xb) / 2, y + 0.16, label, fontsize=7.4, ha="center", va="bottom", color=color,
                        bbox=dict(fc="white", ec="none", pad=0.4, alpha=0.85), zorder=3)
                y -= cost["msg"] * ROW
                continue

        out = Path(path)
        fig.savefig(out, bbox_inches="tight", facecolor="white")
        plt.close(fig)
        return out
