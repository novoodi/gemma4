#!/usr/bin/env python3
"""
후보 상위 N건 → 정답 초안(labeled.json). 초안은 **로컬 규칙**으로만 만들고(외부 LLM 금지) 사람이 검토한다.
검토 시트 labeled_review.md 에 초안 정답을 나열해 수정 지시를 받는다.

규칙:
  date_expressions  요일·상대일·날짜·시각 표현 (메시지 전체에서)
  date_absolute     chat_date 기준 "(이번/다음 주) X요일" 이 하나로 수렴하면 계산, 아니면 null
  places            지명 사전 + "XX역/XX동/XX구" 패턴
  purpose           데이터셋 summary 문장 (검토 시 다듬기)
  likes/dislikes    "~좋아/먹고 싶/가고 싶" 앞 명사구, "~싫/별로/못 먹" 앞 명사구 (거칠음)
  availability      요일·시간대 + 가능/불가 신호가 같은 메시지에 있을 때
"""
import argparse
import datetime as dt
import json
import re
from pathlib import Path

WEEK = "월화수목금토일"
PLACES = ["홍대", "강남", "신촌", "건대", "잠실", "이태원", "성수", "종로", "합정", "망원", "여의도", "한강", "서면", "동성로", "명동",
          "을지로", "연남", "상수", "압구정", "신사", "가로수길", "판교", "분당", "일산", "수유", "노원", "혜화", "대학로", "사당",
          "신림", "구로", "영등포", "용산", "왕십리", "수원", "인천", "부산", "대구", "대전", "광주", "경복궁", "북촌", "삼청동",
          "송도", "해운대", "광안리", "강릉", "제주", "춘천", "천안", "청주", "전주"]
PLACE_RE = re.compile(r"([가-힣]{1,4}(?:역|동|구|시장|공원|대학교|대))(?![가-힣])")
DATE_RE = re.compile(r"((?:이번\s*주|담주|다음\s*주|이번|다음)?\s*[월화수목금토일]요일|주말|평일|내일|모레|오늘|(?:\d{1,2}월\s*)?\d{1,2}일|\d{1,2}시(?:\s*반)?|[한두세네]\s*시(?:\s*반)?|저녁|점심|오전|오후|밤)")
LIKE_RE = re.compile(r"([가-힣A-Za-z ]{2,12}?)\s*(?:이|가|는|은|도)?\s*(?:좋아|좋다|좋지|좋음|먹고\s*싶|가고\s*싶|하고\s*싶|땡겨|땡긴다|최고)")
DISLIKE_RE = re.compile(r"([가-힣A-Za-z ]{2,12}?)\s*(?:은|는|이|가|도)?\s*(?:싫|별로|못\s*먹|안\s*좋아|안\s*땡|질렸|지겨)")
AVAIL_OK = re.compile(r"(가능|돼|됨|되지|괜찮|좋아|콜|오케이|ㅇㅋ|ok)", re.I)
AVAIL_NO = re.compile(r"(안\s*돼|안됨|못\s*가|불가|힘들|바쁘|안\s*될|출근|일해|알바)")
JUNK = {"나도", "그거", "이거", "저거", "진짜", "너무", "완전", "그냥", "근데", "그럼"}


def clean_phrase(p: str):
    p = p.strip(" ,.!?~ㅋㅎ")
    p = re.sub(r"^(나는|난|나도|우리|그냥|진짜|너무|완전|근데|그럼|아|어|음)\s*", "", p).strip()
    return p if len(p) >= 2 and p not in JUNK else None


def weekday_abs(chat_date: str, exprs: list[str]):
    try:
        base = dt.date.fromisoformat(chat_date)
    except Exception:
        return None
    cands = set()
    for e in exprs:
        m = re.search(r"(이번\s*주|담주|다음\s*주|이번|다음)?\s*([월화수목금토일])요일", e)
        if not m:
            continue
        wd = WEEK.index(m.group(2))
        delta = (wd - base.weekday()) % 7
        if m.group(1) and re.search(r"다음|담", m.group(1)):
            delta = delta + 7 if delta else 7
        elif delta == 0:
            delta = 7
        cands.add((base + dt.timedelta(days=delta)).isoformat())
    return cands.pop() if len(cands) == 1 else None


def draft(rec: dict) -> dict:
    texts = [m["text"] for m in rec["messages"]]
    joined = "\n".join(texts)
    dates = []
    for m in DATE_RE.finditer(joined):
        v = re.sub(r"\s+", " ", m.group(1)).strip()
        if v not in dates:
            dates.append(v)
    places = [p for p in PLACES if p in joined]
    for m in PLACE_RE.finditer(joined):
        v = m.group(1)
        if v not in places and len(v) >= 2 and not v.startswith(("이", "그", "저", "어")):
            places.append(v)
    likes, dislikes = [], []
    for t in texts:
        for m in DISLIKE_RE.finditer(t):
            v = clean_phrase(m.group(1))
            if v and v not in dislikes:
                dislikes.append(v)
        for m in LIKE_RE.finditer(t):
            v = clean_phrase(m.group(1))
            if v and v not in likes and v not in dislikes:
                likes.append(v)
    avail = []
    for t in texts:
        d = [re.sub(r"\s+", " ", x.group(1)) for x in DATE_RE.finditer(t) if re.search(r"요일|주말|평일|내일|모레|오후|오전|저녁|점심", x.group(1))]
        if not d:
            continue
        if AVAIL_NO.search(t):
            avail.append(f"{d[0]} 불가")
        elif AVAIL_OK.search(t):
            avail.append(f"{d[0]} 가능")
    avail = list(dict.fromkeys(avail))
    return {
        "date_expressions": dates[:6], "date_absolute": weekday_abs(rec["chat_date"], dates), "places": places[:5],
        "purpose": rec["reference_summary"], "likes": likes[:6], "dislikes": dislikes[:6], "availability": avail[:6], "changes": [],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidates", default=".eval-local/evalset/candidates.json")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--out", default=".eval-local/evalset")
    a = ap.parse_args()
    root = json.load(open(a.candidates, encoding="utf-8"))
    sel = root["dialogues"][: a.top]
    for r in sel:
        g = draft(r)
        r["gold"].update(g)
        r["human_review"] = {"reviewer": "", "verified": False, "notes": "규칙 기반 초안 — 검토 필요"}
    out = Path(a.out)
    (out / "labeled.json").write_text(json.dumps({**{k: v for k, v in root.items() if k != "dialogues"}, "dialogues": sel}, ensure_ascii=False, indent=1), encoding="utf-8")
    lines = ["# 정답 초안 검토 시트 (상위 %d건)" % len(sel), "", "규칙 기반 초안. 틀린 항목은 이 파일에 직접 고치거나 번호와 수정 내용을 알려주면 labeled.json 에 반영한다.", ""]
    for i, r in enumerate(sel, 1):
        g = r["gold"]
        lines += [f"## {i}. {r['id']} ({r['n_participants']}인, {r['n_messages']}건, chat_date {r['chat_date']})", "",
                  f"- 데이터셋 요약: {r['reference_summary']}",
                  f"- 날짜 표현: {g['date_expressions']}  → 절대 날짜: {g['date_absolute']}",
                  f"- 장소: {g['places']}", f"- 좋아요: {g['likes']}", f"- 싫어요: {g['dislikes']}", f"- 일정: {g['availability']}",
                  f"- 변경: {g['changes']}", ""]
        lines += ["  본문:"] + [f"  - {m['sender']}: {m['text']}" for m in r["messages"]] + [""]
    (out / "labeled_review.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"labeled: {len(sel)} → {out / 'labeled.json'}, review sheet → {out / 'labeled_review.md'}")
    print("date_absolute resolved:", sum(1 for r in sel if r["gold"]["date_absolute"]), "/", len(sel))


if __name__ == "__main__":
    main()
