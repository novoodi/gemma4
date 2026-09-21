#!/usr/bin/env python3
"""후기 검색 평가 채점 — RetrievalEvalRunner JSONL → 리트리버별·질의 종류별 top-1/top-3 적중률, MRR, 지연."""
from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()

    rows, meta = [], {}
    for line in open(a.results, encoding="utf-8"):
        if not line.strip():
            continue
        o = json.loads(line)
        if o.get("type") == "meta":
            meta.update(o)
        else:
            rows.append(o)

    names = [k for k in ("keyword", "semantic") if any(k in r for r in rows)]
    kinds = ["lexical", "paraphrase", "intent", "all"]
    stats = {n: {k: {"n": 0, "top1": 0, "top3": 0, "rr": 0.0} for k in kinds} for n in names}
    lat = {n: [] for n in names}
    for r in rows:
        for n in names:
            got = r.get(n, [])
            lat[n].append(r.get(f"{n}Ms", 0))
            for k in (r["kind"], "all"):
                s = stats[n][k]
                s["n"] += 1
                if got and got[0] == r["gold"]:
                    s["top1"] += 1
                if r["gold"] in got:
                    s["top3"] += 1
                    s["rr"] += 1.0 / (got.index(r["gold"]) + 1)

    def cell(s, key):
        return "n/a" if not s["n"] else f"{100.0 * s[key] / s['n']:.1f}%"

    lines = [f"# 후기 검색 평가 — {Path(a.results).name}", "",
             f"기기: {meta.get('device', '?')} · 후기 {meta.get('feedbacks')}건 · 질의 {len(rows)}건 · top-k {meta.get('topK')} · 인덱싱 {meta.get('indexMs')} ms", "",
             "| 리트리버 | 질의 종류 | n | top-1 | top-3 | MRR | 지연 p50 (ms) |", "|---|---|---:|---:|---:|---:|---:|"]
    for n in names:
        p50 = statistics.median(lat[n]) if lat[n] else "n/a"
        for k in kinds:
            s = stats[n][k]
            mrr = "n/a" if not s["n"] else f"{s['rr'] / s['n']:.3f}"
            lines.append(f"| {n} | {k} | {s['n']} | {cell(s, 'top1')} | {cell(s, 'top3')} | {mrr} | {p50 if k == 'all' else ''} |")
    md = "\n".join(lines)
    print(md)
    if a.out:
        out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
        stem = Path(a.results).stem
        (out / f"{stem}.md").write_text(md, encoding="utf-8")
        (out / f"{stem}.summary.json").write_text(json.dumps({"meta": meta, "stats": stats,
            "latency_p50": {n: (statistics.median(lat[n]) if lat[n] else None) for n in names}}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\nwrote → {out / (stem + '.md')}")


if __name__ == "__main__":
    main()
