#!/usr/bin/env python3
"""
정답 있는 평가셋(합성·사람 라벨) 채점 — OnDeviceEvalRunner JSONL + gold → 슬롯 보존·선호 P/R/F1·변경 처리.

지표:
  요약 슬롯 보존   요약문(summaryRaw)에 정답 날짜 표현·장소·목적 핵심어가 포함되는가 (완화 매칭: 정규화 후 부분 문자열)
  선호 P/R/F1     compress preferences(접두사 분리) vs gold likes/dislikes — 토큰 자카드 ≥ 0.5 이면 일치. 극성 뒤집힘 별도 집계
  일정 재현율     compress availability vs gold availability — 토큰 자카드 ≥ 0.4
  변경 처리       gold.changes 의 폐기값(from)이 요약문·availability 에 '최종값'으로 남는가 (합집합 병합 한계 수치화)
  PII             주입 이름·번호 잔존 (bulk 채점과 동일)

사용: python scripts/eval/score_slots.py <results.jsonl> --evalset .eval-local/evalset/synthetic.json [--out dir]
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
from collections import Counter
from pathlib import Path

JSON_BLOCK = re.compile(r"\{[\s\S]*\}")
PREFIX = re.compile(r"^(좋아요|싫어요|선호|비선호|불호|좋아함|싫어함|싫음)\s*[:：]?\s*")
NUM_KO = {"한": "1", "두": "2", "세": "3", "네": "4", "다섯": "5", "여섯": "6", "일곱": "7", "여덟": "8", "아홉": "9", "열": "10"}


def norm(s: str) -> str:
    s = s.lower()
    s = re.sub(r"\s+", "", s)
    s = re.sub(r"(요일)", "", s)  # 토요일 → 토
    for k, v in NUM_KO.items():
        s = s.replace(k + "시", v + "시")
    s = s.replace("오후", "").replace("저녁", "").replace("점심", "").replace("낮", "")
    return s


GENERIC = {"것", "곳", "데", "거", "음식", "장소", "분위기", "느낌", "스타일", "쪽", "이번에는", "같은"}


def tokens(s: str) -> set:
    s = re.sub(r"\([^)]*\)", "", s)  # gold 의 "(2명)" 같은 주석 제거
    return {t for t in re.findall(r"[가-힣a-z0-9]{2,}", s.lower()) if t not in GENERIC}


def match(a: set, b: set, thr: float) -> bool:
    if not a or not b:
        return False
    return jac(a, b) >= thr or a <= b or b <= a


def jac(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


def parse_status(raw):
    m = JSON_BLOCK.search(raw or "")
    if not m:
        return None
    s = re.sub(r",\s*]", "]", m.group()); s = re.sub(r",\s*}", "}", s)
    try:
        o = json.loads(s)
    except Exception:
        return None
    return {k: [str(x).strip() for x in o.get(k, []) if str(x).strip()] if isinstance(o.get(k), list) else []
            for k in ("participants", "preferences", "availability")}


def contains_any(text: str, cands: list[str]) -> bool:
    nt = norm(text)
    for c in cands:
        c = re.sub(r"\([^)]*\)", "", c).strip()
        if not c:
            continue
        nc = norm(c)
        if nc and nc in nt:
            return True
        # 날짜 표현은 요일 글자 하나라도 (토/일/금) + 시각 일치면 인정
        m = re.search(r"([월화수목금토일])", c)
        if m and (m.group(1) + "요일" in text or m.group(1) in nt):
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results"); ap.add_argument("--evalset", required=True); ap.add_argument("--out", default=None)
    ap.add_argument("--jaccard", type=float, default=0.34)
    a = ap.parse_args()

    gold = {d["id"]: d for d in json.load(open(a.evalset, encoding="utf-8"))["dialogues"]}
    rows = [json.loads(l) for l in open(a.results, encoding="utf-8") if l.strip()]
    rows = [r for r in rows if r.get("type") == "result" and "error" not in r and r["id"] in gold]

    slot = Counter(); slot_n = Counter()
    pref_tp = pref_fp = pref_fn = polarity_flip = 0
    avail_tp = avail_fn = avail_pred = 0
    change_n = stale_in_summary = stale_in_avail = final_in_summary = 0
    pii_leak_raw = pii_leak_scr = 0
    per_case = {}
    for r in rows:
        g = gold[r["id"]]["gold"]; case = gold[r["id"]].get("case", "?")
        s = r["summaryRaw"]
        # 슬롯 보존
        checks = {"date": g["date_expressions"], "place": g["places"], "purpose": [g["purpose"]] if g.get("purpose") else []}
        for k, cands in checks.items():
            if not cands:
                continue
            slot_n[k] += 1
            ok = contains_any(s, cands) if k != "purpose" else jac(tokens(cands[0]), tokens(s)) > 0 or any(t in s for t in tokens(cands[0]))
            slot[k] += bool(ok)
            per_case.setdefault(case, Counter())[f"{k}_n"] += 1; per_case[case][k] += bool(ok)
        # 선호
        p = parse_status(r.get("compressRaw", ""))
        if p is not None:
            preds = []
            for x in p["preferences"]:
                m = PREFIX.match(x)
                pol = "dislike" if m and m.group(1) in ("싫어요", "비선호", "불호", "싫어함", "싫음") else "like"
                body = PREFIX.sub("", x).strip()
                if body:
                    preds.append((pol, body))
            golds = [("like", x) for x in g["likes"]] + [("dislike", x) for x in g["dislikes"]]
            matched_g = set()
            for pol, body in preds:
                best = None; best_key = (0, False)
                for gi, (gpol, gb) in enumerate(golds):
                    key = (jac(tokens(body), tokens(gb)), gpol == pol)  # 동점이면 같은 극성 우선
                    if key > best_key:
                        best, best_key = gi, key
                if best is not None and match(tokens(body), tokens(golds[best][1]), a.jaccard):
                    if golds[best][0] == pol:
                        pref_tp += 1; matched_g.add(best)
                    else:
                        polarity_flip += 1; pref_fp += 1
                else:
                    pref_fp += 1
            pref_fn += len(golds) - len(matched_g)
            # 일정
            avail_pred += len(p["availability"])
            for ga in g["availability"]:
                hit = any(match(tokens(ga), tokens(x), 0.34) or norm(re.sub(r"\([^)]*\)", "", ga)) in norm(x) for x in p["availability"])
                avail_tp += hit; avail_fn += not hit
            # 변경 처리
            for ch in g["changes"]:
                change_n += 1
                stale_in_summary += contains_any(s, [ch["from"]]) and not contains_any(s, [ch["to"]])
                final_in_summary += contains_any(s, [ch["to"]])
                stale_in_avail += any(norm(ch["from"]) in norm(x) for x in p["availability"]) and ch["field"] == "date"
        # PII
        pii = g["pii_injected"]
        items = list(pii["in_text_names"]) + pii["phones"] + pii["emails"]
        pii_leak_raw += any(v in s for v in items); pii_leak_scr += any(v in r["summaryScrubbed"] for v in items)

    def pct(x, n): return "n/a" if not n else f"{100.0 * x / n:.1f}% ({x}/{n})"
    prec = pref_tp / (pref_tp + pref_fp) if pref_tp + pref_fp else 0; rec = pref_tp / (pref_tp + pref_fn) if pref_tp + pref_fn else 0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
    lines = [f"# 슬롯 평가 — {Path(a.results).name} × {Path(a.evalset).name}", "", f"대화 {len(rows)}건 (오류·미매칭 제외)", "",
             "| 지표 | 값 | 비고 |", "|---|---|---|",
             f"| 요약: 날짜 표현 보존 | {pct(slot['date'], slot_n['date'])} | 정규화 부분 일치 |",
             f"| 요약: 장소 보존 | {pct(slot['place'], slot_n['place'])} | |",
             f"| 요약: 목적 보존 | {pct(slot['purpose'], slot_n['purpose'])} | 핵심어 하나 이상 |",
             f"| 선호·불호 정밀도 / 재현율 / F1 | {prec:.3f} / {rec:.3f} / {f1:.3f} | 토큰 자카드 ≥ {a.jaccard}, TP={pref_tp} FP={pref_fp} FN={pref_fn} |",
             f"| 극성 뒤집힘 | {polarity_flip} | 좋아요↔싫어요 |",
             f"| 일정 재현율 | {pct(avail_tp, avail_tp + avail_fn)} | 예측 {avail_pred}건 |",
             f"| 변경 처리: 최종값이 요약에 있음 | {pct(final_in_summary, change_n)} | changes 기준 |",
             f"| 변경 처리: 폐기값만 요약에 남음 | {pct(stale_in_summary, change_n)} | 오류 |",
             f"| 변경 처리: 폐기 날짜가 availability에 잔존 | {pct(stale_in_avail, sum(1 for r in rows for c in gold[r['id']]['gold']['changes'] if c['field'] == 'date'))} | 합집합 병합 한계 |",
             f"| PII 누출 (건) — 단독 / 스크러버 후 | {pct(pii_leak_raw, len(rows))} / {pct(pii_leak_scr, len(rows))} | |",
             "", "케이스별 슬롯 보존 (date/place/purpose):", ""]
    for c, cnt in sorted(per_case.items()):
        lines.append(f"- {c}: " + ", ".join(f"{k} {cnt[k]}/{cnt[k + '_n']}" for k in ("date", "place", "purpose") if cnt[k + '_n']))
    md = "\n".join(lines); print(md)
    if a.out:
        out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
        (out / f"{Path(a.results).stem}.slots.md").write_text(md, encoding="utf-8")


if __name__ == "__main__":
    main()
