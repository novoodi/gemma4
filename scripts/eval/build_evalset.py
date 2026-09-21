#!/usr/bin/env python3
"""
AI Hub 한국어 대화 요약 Validation ZIP → 모이미 평가셋 생성기.

산출물(.eval-local/evalset/, git 제외):
  candidates.json        사람이 정답을 달 모임 관련 후보 대화(슬롯 평가용, 상위 N건)
  candidates_review.md   후보 선별용 검토 시트
  bulk_ondevice.json     정답 불필요 지표용 대량 대화(PII 누출·형식·지연·요약 명사 보존)
  scrubber_bulk.jsonl    PiiScrubber 단독 대량 검증용(JVM 테스트 입력)
  manifest.json          생성 조건·건수·시드

핵심 처리 — 마스킹 토큰 치환:
  데이터는 이미 익명화돼 있다(#@이름#, #@전번# …). 합성 이름·번호·이메일을 토큰 자리에
  주입하고, 주입한 값을 정답(gold.pii_injected)으로 기록한다. 실존 인물과 무관한 값만 쓴다.

라이선스: 원문이 담긴 산출물은 .eval-local/ 밖으로 내보내지 않는다(재배포 금지).
"""
from __future__ import annotations

import argparse
import json
import random
import re
import zipfile
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

# ── 합성 이름 풀 (일반 단어와 겹치는 이름은 제외: 지우, 나은, 하늘 등) ──────────
SURNAMES = list("김이박최정강조윤장임한오서신권황안송류홍")
GIVEN_NAMES = [
    "민수", "지영", "서준", "하은", "도윤", "수아", "예준", "시우", "하린", "준서",
    "유진", "현우", "채원", "지호", "다은", "건우", "서연", "우진", "은서", "소율",
    "수빈", "정우", "민재", "예린", "태윤", "가은", "승현", "세아", "재원", "혜원",
]
ORG_POOL = ["회사", "학교", "동아리", "학원"]
ADDRESS_POOL = ["역삼동", "성수동", "합정동", "서면", "수성구", "분당", "일산", "노원"]

TOKEN_RE = re.compile(r"#@(이름|시스템|이모티콘|기타|URL|소속|주소|계정|금융|번호|전번|신원)#(?:[^#\s]*#)?")

# PiiScrubber.honorificStopwords 와 동일 — 마스킹되면 안 되는 호칭어(오탐 대조군)
HONORIFIC_STOPWORDS = {
    "선생님", "고객님", "사장님", "부모님", "어머님", "아버님", "할머님", "아주머님", "아저씨", "아가씨",
}
HONORIFIC_RE = re.compile(r"[가-힣]{2,4}(님|씨)")

# ── 모임 관련성 키워드 (범주별) ───────────────────────────────────────────────
KW = {
    "schedule": ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일", "주말", "다음주", "다음 주",
                 "이번주", "이번 주", "몇시", "몇 시", "시에", "오후", "저녁", "점심", "언제", "날짜", "시간", "담주",
                 "내일", "모레", "평일"],
    "place": ["어디서", "어디로", "어디", "역에", "카페", "술집", "맛집", "식당", "근처", "장소", "홍대", "강남", "신촌",
              "건대", "잠실", "이태원", "성수", "종로", "합정", "서면", "동성로", "역 앞"],
    "meet": ["만나", "만날", "만남", "모임", "모이", "모일", "약속", "보자", "볼까", "볼래", "회식", "뒷풀이", "놀자",
             "가자", "갈까", "갈래", "먹자", "먹을까", "예약", "정모", "번개", "모여"],
    "preference": ["좋아", "싫어", "별로", "취향", "선호", "조용", "시끄", "맛있", "분위기", "싫", "괜찮", "싼", "비싸",
                   "예산", "가격"],
}

PHONE_TEMPLATES = ["내 번호 {phone}로 연락해", "번호 {phone} 저장해둬", "{phone} 이거 내 번호야"]
EMAIL_TEMPLATES = ["메일은 {email}로 보내줘", "{email} 여기로 파일 보내"]


def synth_phone(rng: random.Random) -> str:
    return f"010-{rng.randint(2000, 9999)}-{rng.randint(1000, 9999)}"


def synth_email(rng: random.Random) -> str:
    return f"{rng.choice(['moim', 'chat', 'friend', 'test'])}{rng.randint(10, 99)}@example.com"


def load_dialogues(zip_path: Path):
    z = zipfile.ZipFile(zip_path)
    for name in z.namelist():
        if not name.endswith(".json"):
            continue
        topic = Path(name).stem
        for x in json.load(z.open(name))["data"]:
            yield topic, x


def relevance(text: str) -> tuple[int, int]:
    """(범주 적중 수 0~4, 총 키워드 적중 수)"""
    cats = 0
    total = 0
    for words in KW.values():
        hits = sum(text.count(w) for w in words)
        if hits:
            cats += 1
        total += hits
    return cats, total


def build_record(topic: str, x: dict, rng: random.Random, augment: bool) -> dict:
    info = x["header"]["dialogueInfo"]
    pids = [p["participantID"] for p in x["header"]["participantsInfo"]]
    # 참가자 합성 실명 (senderName) — 서로 다른 성·이름
    surnames = rng.sample(SURNAMES, len(pids))
    givens = rng.sample(GIVEN_NAMES, len(pids) + 2)
    participants = {pid: surnames[i] + givens[i] for i, pid in enumerate(pids)}
    third_party = givens[len(pids):]          # 명단에 없는 제3자 이름 2개

    injected = {
        "participant_names": list(participants.values()),
        "in_text_names": Counter(),           # 본문에 주입된 이름 → 횟수
        "third_party_names": third_party,
        "phones": [], "emails": [], "addresses": [],
    }
    name_cycle_idx = 0

    def name_pool_for(speaker_pid: str) -> list[str]:
        others = [participants[p][1:] for p in pids if p != speaker_pid]  # 상대 참가자 given name
        return others + third_party

    messages = []
    for u in x["body"]["dialogue"]:
        pid = u["participantID"]
        text = u["utterance"]

        def repl(m: re.Match) -> str:
            nonlocal name_cycle_idx
            kind = m.group(1)
            if kind == "이름":
                pool = name_pool_for(pid)
                nm = pool[name_cycle_idx % len(pool)]
                name_cycle_idx += 1
                injected["in_text_names"][nm] += 1
                return nm
            if kind in ("번호", "전번"):
                ph = synth_phone(rng); injected["phones"].append(ph); return ph
            if kind == "계정":
                em = synth_email(rng); injected["emails"].append(em); return em
            if kind == "URL":
                return "링크"
            if kind == "소속":
                return rng.choice(ORG_POOL)
            if kind == "주소":
                ad = rng.choice(ADDRESS_POOL); injected["addresses"].append(ad); return ad
            if kind == "금융":
                return "계좌번호"
            return ""  # 시스템/이모티콘/기타/신원 → 제거

        text = TOKEN_RE.sub(repl, text)
        text = re.sub(r"\s{2,}", " ", text).strip()
        if not text:
            continue
        messages.append({"pid": pid, "sender": participants.get(pid, pid), "text": text,
                         "date": u.get("date"), "time": u.get("time")})

    augmented = []
    if augment and messages:
        if rng.random() < 0.30:
            ph = synth_phone(rng); injected["phones"].append(ph)
            i = rng.randrange(len(messages))
            messages.insert(i + 1, {**messages[i], "text": rng.choice(PHONE_TEMPLATES).format(phone=ph)})
            augmented.append("phone")
        if rng.random() < 0.15:
            em = synth_email(rng); injected["emails"].append(em)
            i = rng.randrange(len(messages))
            messages.insert(i + 1, {**messages[i], "text": rng.choice(EMAIL_TEMPLATES).format(email=em)})
            augmented.append("email")

    full_text = "\n".join(m["text"] for m in messages)
    injected_names = set(injected["in_text_names"])
    honorific_nonname = sorted({
        m.group() for m in HONORIFIC_RE.finditer(full_text)
        if m.group()[:-1] not in injected_names and m.group() not in injected_names
    })
    decoys = sorted((HONORIFIC_STOPWORDS & set(re.findall(r"[가-힣]+", full_text))) | set(honorific_nonname))

    cats, total = relevance(full_text)
    return {
        "id": f"aihub-{topic}-{info['dialogueID'][:8]}",
        "source": "aihub-valid",
        "topic": topic,
        "chat_date": messages[0]["date"] if messages else None,
        "n_participants": len(pids),
        "n_messages": len(messages),
        "relevance": {"categories": cats, "hits": total},
        "participants": [{"pid": p, "name": n} for p, n in participants.items()],
        "messages": messages,
        "reference_summary": x["body"]["summary"],
        "gold": {
            "date_expressions": [], "date_absolute": None, "places": [], "purpose": None,
            "likes": [], "dislikes": [], "availability": [], "changes": [],
            "pii_injected": {
                "participant_names": injected["participant_names"],
                "in_text_names": dict(injected["in_text_names"]),
                "third_party_names": [n for n in third_party if n in injected_names],
                "phones": injected["phones"], "emails": injected["emails"],
            },
            "pii_decoys": decoys,
            "augmented": augmented,
        },
        "human_review": {"reviewer": "", "verified": False, "notes": ""},
    }


def write_review_md(records: list[dict], path: Path) -> None:
    lines = ["# 후보 대화 검토 시트", "",
             "각 후보를 보고 `선택` 열에 O를 적는다. 목표 30건(3인 이상 우선). 원문 포함 — 저장소 밖 반출 금지.", "",
             "| # | 선택 | id | 주제 | 인원 | 메시지 | 관련성 | 데이터셋 요약 |", "|---|---|---|---|---|---|---|---|"]
    for i, r in enumerate(records, 1):
        lines.append(f"| {i} |  | {r['id']} | {r['topic']} | {r['n_participants']} | {r['n_messages']} | "
                     f"{r['relevance']['categories']}/{r['relevance']['hits']} | {r['reference_summary']} |")
    lines += ["", "## 본문 미리보기 (앞 8줄)", ""]
    for i, r in enumerate(records, 1):
        lines.append(f"### {i}. {r['id']} ({r['n_participants']}인, {r['n_messages']}건)")
        for m in r["messages"][:8]:
            lines.append(f"- {m['sender']}: {m['text']}")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", default=r"E:\한국어 대화 요약\Validation\[라벨]한국어대화요약_valid.zip")
    ap.add_argument("--out", default=".eval-local/evalset")
    ap.add_argument("--seed", type=int, default=20260913)
    ap.add_argument("--n-candidates", type=int, default=120)
    ap.add_argument("--n-bulk", type=int, default=320)
    ap.add_argument("--n-scrubber", type=int, default=5000)
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    raw = list(load_dialogues(Path(args.zip)))
    print(f"loaded {len(raw)} dialogues")

    # 1) 후보: 모임 관련성 상위 (범주 ≥3, 메시지 ≥10). 3인 이상 우선, 그다음 점수순.
    scored = []
    for topic, x in raw:
        text = " ".join(u["utterance"] for u in x["body"]["dialogue"])
        cats, total = relevance(text)
        n_utt = len(x["body"]["dialogue"])
        n_p = x["header"]["dialogueInfo"]["numberOfParticipants"]
        if cats >= 3 and n_utt >= 10:
            scored.append(((n_p >= 3, cats, min(total, 12), n_utt), topic, x))
    scored.sort(key=lambda t: t[0], reverse=True)
    cand_raw = scored[: args.n_candidates]
    cand_ids = {x["header"]["dialogueInfo"]["dialogueID"] for _, _, x in cand_raw}
    candidates = [build_record(t, x, random.Random(f"{args.seed}-{x['header']['dialogueInfo']['dialogueID']}"), augment=False)
                  for _, t, x in cand_raw]
    print(f"candidates: {len(candidates)} (3+ participants: {sum(c['n_participants'] >= 3 for c in candidates)})")

    # 2) 대량 온디바이스: 주제별 층화 무작위, 메시지 ≥8, 후보 제외
    by_topic = defaultdict(list)
    for topic, x in raw:
        if x["header"]["dialogueInfo"]["dialogueID"] in cand_ids:
            continue
        if len(x["body"]["dialogue"]) >= 8:
            by_topic[topic].append((topic, x))
    per_topic = args.n_bulk // len(by_topic)
    bulk_raw = []
    for topic, items in sorted(by_topic.items()):
        bulk_raw += rng.sample(items, min(per_topic, len(items)))
    bulk = [build_record(t, x, random.Random(f"{args.seed}-bulk-{x['header']['dialogueInfo']['dialogueID']}"), augment=True)
            for t, x in bulk_raw]
    print(f"bulk: {len(bulk)} (with phone {sum('phone' in b['gold']['augmented'] for b in bulk)}, "
          f"email {sum('email' in b['gold']['augmented'] for b in bulk)})")

    # 3) 스크러버 단독 대량: 무작위 N건, 메시지 ≥4
    pool = [(t, x) for t, x in raw if len(x["body"]["dialogue"]) >= 4]
    scr_raw = rng.sample(pool, min(args.n_scrubber, len(pool)))
    n_phone = n_email = n_name_occ = 0
    with (out / "scrubber_bulk.jsonl").open("w", encoding="utf-8") as f:
        for t, x in scr_raw:
            r = build_record(t, x, random.Random(f"{args.seed}-scr-{x['header']['dialogueInfo']['dialogueID']}"), augment=True)
            pii = r["gold"]["pii_injected"]
            n_phone += len(pii["phones"]); n_email += len(pii["emails"]); n_name_occ += sum(pii["in_text_names"].values())
            f.write(json.dumps({
                "id": r["id"],
                "text": "\n".join(m["text"] for m in r["messages"]),
                "known_names": pii["participant_names"],
                "in_text_names": pii["in_text_names"],
                "third_party_names": pii["third_party_names"],
                "phones": pii["phones"], "emails": pii["emails"],
                "decoys": r["gold"]["pii_decoys"],
            }, ensure_ascii=False) + "\n")
    print(f"scrubber: {len(scr_raw)} (name occurrences {n_name_occ}, phones {n_phone}, emails {n_email})")

    meta = {"version": 1, "created": datetime.now().isoformat(timespec="seconds"), "seed": args.seed,
            "source": "AI Hub 한국어 대화 요약 (Validation)", "license_note": "재배포 금지 — 원문 산출물은 .eval-local 밖 반출 금지"}
    (out / "candidates.json").write_text(json.dumps({**meta, "dialogues": candidates}, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "bulk_ondevice.json").write_text(json.dumps({**meta, "dialogues": bulk}, ensure_ascii=False, indent=1), encoding="utf-8")
    write_review_md(candidates, out / "candidates_review.md")
    (out / "manifest.json").write_text(json.dumps({
        **meta, "total_source_dialogues": len(raw),
        "candidates": len(candidates), "bulk_ondevice": len(bulk), "scrubber_bulk": len(scr_raw),
        "scrubber_gold_counts": {"name_occurrences": n_name_occ, "phones": n_phone, "emails": n_email},
        "candidate_ids": [c["id"] for c in candidates], "bulk_ids": [b["id"] for b in bulk],
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"wrote → {out}")


if __name__ == "__main__":
    main()
