#!/usr/bin/env python3
"""
온디바이스 평가 채점기 — OnDeviceEvalRunner 의 JSONL + 평가셋 정답 → 지표 표(markdown + JSON).

정답 불필요 지표만 계산한다(대량 셋용):
  PII 누출률       Gemma 요약 단독 / PiiScrubber 통과 후 — 주입 이름·전화·이메일 잔존 (건 단위·항목 단위)
  스크러버 과잉 마스킹  요약에 주입 PII가 없는데 마스크가 생긴 건수 (오탐 추정)
  형식 준수         요약 문장 수 2~3, 마크다운 기호 없음
  참조 요약 어휘 보존  데이터셋 summary 의 한글 2자+ 어절(어미 정제) 포함률 — 거친 지표
  성향 압축         JSON 파싱 성공률, constrained 경로 비율, 참석자 재현율(발신자명이 정답), 목록 크기
  지연             요약·압축 p50/p95, 모델 로드

사용: python scripts/eval/score_ondevice.py <results.jsonl> [--evalset .eval-local/evalset/bulk_ondevice.json] [--out docs/evidence/<날짜>/eval]
원문·요약문은 출력 표에 포함하지 않는다(집계값만).
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
from collections import Counter
from pathlib import Path

JSON_BLOCK = re.compile(r"\{[\s\S]*\}")
MD_CHARS = re.compile(r"[*#\[\]`]")
SENT_SPLIT = re.compile(r"[.!?。]\s*|\n+")
# 참조 요약의 서술 어미 — 어절 뒤에서 잘라 어간 근사(거친 정제)
SUFFIXES = ["한다고", "하자고", "했다고", "있다고", "라고", "다고", "하기로", "하려고", "했다", "한다", "이다", "있다", "된다",
            "하고", "해서", "하는", "하며", "에서", "에게", "으로", "까지", "부터", "이랑", "라서", "처럼", "보다", "에는", "으며",
            "지만", "하며", "한", "을", "를", "은", "는", "이", "가", "의", "에", "로", "와", "과", "도", "다"]
STOP = {"있다", "한다", "이다", "하다", "그리고", "그래서", "때문", "대해", "대한", "이야기", "말한다", "말하고", "묻는다", "한다고",
        "서로", "같이", "함께", "오늘", "지금", "얘기"}


def pct(a, b):
    return "n/a" if not b else f"{100.0 * a / b:.1f}% ({a}/{b})"


def p50_p95(xs):
    if not xs:
        return ("n/a", "n/a")
    xs = sorted(xs)
    return (f"{statistics.median(xs):.0f}", f"{xs[min(len(xs) - 1, int(round(0.95 * (len(xs) - 1))))]:.0f}")


def parse_status(raw: str):
    m = JSON_BLOCK.search(raw or "")
    if not m:
        return None
    s = re.sub(r",\s*]", "]", m.group()); s = re.sub(r",\s*}", "}", s)
    try:
        o = json.loads(s)
    except Exception:
        return None
    if not isinstance(o, dict):
        return None
    return {k: [str(x).strip() for x in o.get(k, []) if str(x).strip()] if isinstance(o.get(k), list) else []
            for k in ("participants", "preferences", "availability")}


def stem_tokens(text: str):
    toks = set()
    for w in re.findall(r"[가-힣]{2,}", text):
        for suf in SUFFIXES:
            if w.endswith(suf) and len(w) - len(suf) >= 2:
                w = w[: -len(suf)]
                break
        if len(w) >= 2 and w not in STOP:
            toks.add(w)
    return toks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--evalset", default=".eval-local/evalset/bulk_ondevice.json")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    gold = {d["id"]: d for d in json.load(open(args.evalset, encoding="utf-8"))["dialogues"]}
    rows, metas = [], []
    for line in open(args.results, encoding="utf-8"):
        if not line.strip():
            continue
        o = json.loads(line)
        (metas if o.get("type") == "meta" else rows).append(o)

    n = len(rows)
    errors = [r for r in rows if "error" in r]
    ok = [r for r in rows if "error" not in r]

    # ── PII ──
    leak_raw_rows = leak_scr_rows = rows_with_pii = 0
    item_gold = Counter(); item_leak_raw = Counter(); item_leak_scr = Counter()
    over_mask_rows = 0
    known_leak_raw = known_leak_scr = third_leak_raw = third_leak_scr = 0
    for r in ok:
        g = gold[r["id"]]["gold"]["pii_injected"]
        knownGiven = {nm[1:] for nm in g["participant_names"] if len(nm) == 3}
        items = [("name", nm) for nm in g["in_text_names"]] + [("phone", p) for p in g["phones"]] + [("email", e) for e in g["emails"]]
        items += [("participant_full", nm) for nm in g["participant_names"]]
        raw, scr = r["summaryRaw"], r["summaryScrubbed"]
        any_gold = any(v in "\n".join(m["text"] for m in gold[r["id"]]["messages"]) for _, v in items)
        if any_gold:
            rows_with_pii += 1
        lr = ls = False
        for kind, v in items:
            item_gold[kind] += 1
            if v in raw:
                item_leak_raw[kind] += 1; lr = True
                if kind == "name":
                    if v in knownGiven: known_leak_raw += 1
                    else: third_leak_raw += 1
            if v in scr:
                item_leak_scr[kind] += 1; ls = True
                if kind == "name":
                    if v in knownGiven: known_leak_scr += 1
                    else: third_leak_scr += 1
        leak_raw_rows += lr; leak_scr_rows += ls
        if not lr and r.get("redactions", 0) > 0:
            over_mask_rows += 1

    # ── 형식 ──
    sent_ok = md_free = 0
    sent_counts = []
    for r in ok:
        s = r["summaryRaw"].strip()
        k = len([x for x in SENT_SPLIT.split(s) if x.strip()])
        sent_counts.append(k)
        sent_ok += 2 <= k <= 3
        md_free += not MD_CHARS.search(s)

    # ── 참조 요약 어휘 보존 ──
    cover = []
    for r in ok:
        ref = stem_tokens(gold[r["id"]]["reference_summary"])
        if not ref:
            continue
        out = r["summaryRaw"]
        cover.append(sum(1 for t in ref if t in out) / len(ref))

    # ── 성향 압축 ──
    comp = [r for r in ok if "compressRaw" in r]
    parsed = [(r, parse_status(r["compressRaw"])) for r in comp]
    parse_ok = [(r, p) for r, p in parsed if p is not None]
    path_cnt = Counter(r.get("compressPath") for r in comp)
    part_recall = []
    pref_sizes, avail_sizes = [], []
    pref_prefixed = pref_total = pref_hollow = 0
    for r, p in parse_ok:
        senders = set(gold[r["id"]]["gold"]["pii_injected"]["participant_names"])
        givens = {s[1:] for s in senders if len(s) == 3}
        hit = sum(1 for s in senders if any(s in x or (s[1:] in x if len(s) == 3 else False) for x in p["participants"]))
        part_recall.append(hit / len(senders) if senders else 1.0)
        pref_sizes.append(len(p["preferences"])); avail_sizes.append(len(p["availability"]))
        for x in p["preferences"]:
            pref_total += 1
            pref_prefixed += bool(re.match(r"^(좋아요|싫어요|선호|비선호|불호)\s*[:：]", x))
            pref_hollow += bool(re.fullmatch(r"(좋아요|싫어요|선호|비선호|불호)\s*[:：]?\s*", x))  # 접두사만 있고 내용 없음

    # ── 지연 ──
    t_sum = [r["tSummaryMs"] for r in ok]
    t_cmp = [r["tCompressMs"] for r in comp]
    load = next((m["modelLoadMs"] for m in metas if "modelLoadMs" in m), None)
    device = next((m["device"] for m in metas if "device" in m), "?")

    lines = [f"# 온디바이스 평가 결과 — {Path(args.results).name}", "",
             f"기기: {device} · 대화 {n}건 (오류 {len(errors)}건) · 모델 로드 {load} ms", "",
             "| 지표 | 값 | 비고 |", "|---|---|---|",
             f"| PII 누출 (건 단위) — Gemma 요약 단독 | {pct(leak_raw_rows, len(ok))} | 주입 PII가 요약에 하나라도 남은 대화 |",
             f"| PII 누출 (건 단위) — 스크러버 통과 후 | {pct(leak_scr_rows, len(ok))} | 클라우드로 실제 나가는 텍스트 기준 |",
             f"| 이름 누출 (항목) — 단독 / 스크러버 후 | {pct(item_leak_raw['name'], item_gold['name'])} / {pct(item_leak_scr['name'], item_gold['name'])} | 본문 주입 given name |",
             f"|   └ 명단 내 이름 (발신자) | {known_leak_raw} → {known_leak_scr} | 스크러버 명단 대조 대상 |",
             f"|   └ 명단 외 이름 (제3자) | {third_leak_raw} → {third_leak_scr} | 호칭 백스톱만 적용 |",
             f"| 전화 누출 — 단독 / 스크러버 후 | {pct(item_leak_raw['phone'], item_gold['phone'])} / {pct(item_leak_scr['phone'], item_gold['phone'])} | |",
             f"| 이메일 누출 — 단독 / 스크러버 후 | {pct(item_leak_raw['email'], item_gold['email'])} / {pct(item_leak_scr['email'], item_gold['email'])} | |",
             f"| 과잉 마스킹 대화 | {pct(over_mask_rows, len(ok))} | 요약에 주입 PII가 없는데 마스크 발생 (오탐 추정) |",
             f"| 요약 2~3문장 준수 | {pct(sent_ok, len(ok))} | 문장 수 중앙값 {statistics.median(sent_counts) if sent_counts else 'n/a'} |",
             f"| 마크다운 기호 없음 | {pct(md_free, len(ok))} | `* # [ ]` 부재 |",
             f"| 참조 요약 어휘 보존 (평균) | {statistics.mean(cover) * 100:.1f}% (n={len(cover)}) | 데이터셋 summary 어절 포함률, 거친 지표 |" if cover else "| 참조 요약 어휘 보존 | n/a | |",
             f"| 압축 JSON 파싱 성공 | {pct(len(parse_ok), len(comp))} | 파이프라인 정규식 repair 기준 |",
             f"| constrained 경로 비율 | {pct(path_cnt.get('constrained', 0), len(comp))} | 나머지는 자유텍스트 폴백 |",
             f"| 참석자 재현율 (평균) | {statistics.mean(part_recall) * 100:.1f}% | 발신자명이 정답 (자동) |" if part_recall else "| 참석자 재현율 | n/a | |",
             f"| 선호 항목 접두사 준수 | {pct(pref_prefixed, pref_total)} | 좋아요:/싫어요: |",
             f"| 내용 없는 선호 항목 (접두사만) | {pct(pref_hollow, pref_total)} | 예: \"좋아요:\" — 프로필·Reflection 오염 원인 |",
             f"| 선호·일정 목록 크기 (중앙값) | {statistics.median(pref_sizes) if pref_sizes else 'n/a'} / {statistics.median(avail_sizes) if avail_sizes else 'n/a'} | 정답 채점은 라벨셋에서 |",
             f"| 요약 지연 p50 / p95 (ms) | {' / '.join(p50_p95(t_sum))} | n={len(t_sum)} |",
             f"| 압축 지연 p50 / p95 (ms) | {' / '.join(p50_p95(t_cmp))} | n={len(t_cmp)} |",
             ]
    if errors:
        lines += ["", "오류 유형:", ""] + [f"- {k}: {v}" for k, v in Counter(e["error"].split(":")[0] for e in errors).items()]
    md = "\n".join(lines)
    print(md)

    summary = {
        "results_file": Path(args.results).name, "device": device, "n": n, "errors": len(errors), "model_load_ms": load,
        "pii": {"rows_leak_raw": leak_raw_rows, "rows_leak_scrubbed": leak_scr_rows, "rows_ok": len(ok),
                "item_gold": dict(item_gold), "item_leak_raw": dict(item_leak_raw), "item_leak_scrubbed": dict(item_leak_scr),
                "known_name_leak_raw": known_leak_raw, "known_name_leak_scrubbed": known_leak_scr,
                "third_party_leak_raw": third_leak_raw, "third_party_leak_scrubbed": third_leak_scr,
                "over_mask_rows": over_mask_rows},
        "format": {"sent_ok": sent_ok, "md_free": md_free, "sent_counts": Counter(sent_counts)},
        "reference_coverage_mean": statistics.mean(cover) if cover else None,
        "compress": {"n": len(comp), "parse_ok": len(parse_ok), "path": dict(path_cnt),
                     "participant_recall_mean": statistics.mean(part_recall) if part_recall else None,
                     "pref_prefixed": pref_prefixed, "pref_total": pref_total, "pref_hollow": pref_hollow},
        "latency_ms": {"summary_p50_p95": p50_p95(t_sum), "compress_p50_p95": p50_p95(t_cmp)},
    }
    if args.out:
        out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
        stem = Path(args.results).stem
        (out / f"{stem}.md").write_text(md, encoding="utf-8")
        (out / f"{stem}.summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
        print(f"\nwrote → {out / (stem + '.md')}")


if __name__ == "__main__":
    main()
