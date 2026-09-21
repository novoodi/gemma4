#!/usr/bin/env python3
"""
추천 평가 채점 — RecommendationEvalRunner JSONL → 조건별(none/keyword/semantic) 성공률·재시도·검증 효과·날짜 정확도.

검증 유무 비교: 1회차(attempt 1) Reflection 위반 수·Guardrail CLOSED 여부 = "검증 없음" 결과,
최종 결과 = "검증 있음". 폴백 성공(재시도 소진 후 Reflection 미충족 반환)은 별도 집계.
"""
from __future__ import annotations

import argparse
import json
import statistics
from collections import Counter, defaultdict
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("results"); ap.add_argument("--out", default=None); a = ap.parse_args()
    rows, meta = [], {}
    for l in open(a.results, encoding="utf-8"):
        if not l.strip():
            continue
        o = json.loads(l)
        (meta.update(o) if o.get("type") == "meta" else rows.append(o))

    by = defaultdict(list)
    for r in rows:
        by[r["condition"]].append(r)

    lines = [f"# 추천 평가 — {Path(a.results).name}", "", f"기기: {meta.get('device')} · 시나리오 {len({r['id'] for r in rows})}건 · 실행 {len(rows)}회 · 후기 {meta.get('feedbacks')}건", "",
             "| 조건 | 실행 | 성공 | 예외/실패 | 평균 시도 | 3회 소진 | 1회차 Reflection 위반 | 최종 Reflection 위반 | 1회차 Guardrail 실패 | 최종 Guardrail 실패 | UNKNOWN 장소 비율 | 후기 주입 | 날짜 일치 | p50 (s) |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    summary = {}
    for cond, rs in by.items():
        n = len(rs); ok = [r for r in rs if r.get("success")]
        fail = n - len(ok)
        attempts = [r["attempts"] for r in rs if "attempts" in r]
        exhausted = sum(1 for r in rs if r.get("attempts") == 3)
        ref1 = ref_final = g1 = g_final = 0; unknown = places = 0; rag = 0; date_ok = date_n = 0
        for r in rs:
            ev = r.get("events", [])
            refl = [e for e in ev if e["event"] == "ReflectionEvaluated"]
            guard = [e for e in ev if e["event"] == "GuardrailEvaluated"]
            if refl:
                ref1 += len(refl[0]["violations"]); ref_final += len(refl[-1]["violations"])
            if guard:
                g1 += not guard[0]["passed"]; g_final += not guard[-1]["passed"]
                unknown += guard[-1].get("unknownCount", 0)
            places += len(r.get("places", []))
            rag += any(e.get("hasRag") for e in ev if e["event"] == "PromptGenerated")
            if r.get("goldDateAbsolute") and r.get("meetingDate"):
                date_n += 1; date_ok += r["meetingDate"] == r["goldDateAbsolute"]
        p50 = statistics.median(r["elapsedMs"] for r in rs) / 1000 if rs else 0
        lines.append(f"| {cond} | {n} | {len(ok)} | {fail} | {statistics.mean(attempts):.2f} | {exhausted} | {ref1} | {ref_final} | {g1} | {g_final} | "
                     f"{(100.0 * unknown / places if places else 0):.0f}% | {rag}/{n} | {date_ok}/{date_n} | {p50:.0f} |" if attempts else f"| {cond} | {n} | 0 | {fail} | | | | | | | | | | |")
        summary[cond] = {"n": n, "success": len(ok), "attempts_mean": statistics.mean(attempts) if attempts else None, "exhausted": exhausted,
                         "reflection_violations_attempt1": ref1, "reflection_violations_final": ref_final,
                         "guardrail_fail_attempt1": g1, "guardrail_fail_final": g_final, "unknown_places": unknown, "places": places,
                         "rag_injected": rag, "date_ok": date_ok, "date_n": date_n, "p50_s": p50}
    # 시나리오별 최종 위반 (조건 무관) — 어떤 케이스가 어려운지
    lines += ["", "시나리오별 (조건 합산): 성공/실행, 1회차→최종 Reflection 위반", ""]
    per = defaultdict(lambda: [0, 0, 0, 0])
    for r in rows:
        p = per[r["id"]]; p[1] += 1; p[0] += bool(r.get("success"))
        refl = [e for e in r.get("events", []) if e["event"] == "ReflectionEvaluated"]
        if refl:
            p[2] += len(refl[0]["violations"]); p[3] += len(refl[-1]["violations"])
    for sid, (s, n, v1, vf) in sorted(per.items()):
        lines.append(f"- {sid}: {s}/{n}, 위반 {v1}→{vf}")
    errs = Counter((r.get("error") or r.get("reason") or "").split(":")[0] for r in rows if not r.get("success"))
    if errs:
        lines += ["", "실패 사유:", ""] + [f"- {k or '(없음)'}: {v}" for k, v in errs.items()]
    md = "\n".join(lines); print(md)
    if a.out:
        out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
        stem = Path(a.results).stem
        (out / f"{stem}.md").write_text(md, encoding="utf-8")
        (out / f"{stem}.summary.json").write_text(json.dumps({"meta": meta, "conditions": summary}, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
