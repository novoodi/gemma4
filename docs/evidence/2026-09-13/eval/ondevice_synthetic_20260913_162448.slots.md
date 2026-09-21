# 슬롯 평가 — ondevice_synthetic_20260913_162448.jsonl × synthetic.json

대화 14건 (오류·미매칭 제외)

| 지표 | 값 | 비고 |
|---|---|---|
| 요약: 날짜 표현 보존 | 100.0% (14/14) | 정규화 부분 일치 |
| 요약: 장소 보존 | 100.0% (13/13) | |
| 요약: 목적 보존 | 100.0% (14/14) | 핵심어 하나 이상 |
| 선호·불호 정밀도 / 재현율 / F1 | 0.733 / 0.512 / 0.603 | 토큰 자카드 ≥ 0.34, TP=22 FP=8 FN=21 |
| 극성 뒤집힘 | 1 | 좋아요↔싫어요 |
| 일정 재현율 | 50.0% (10/20) | 예측 17건 |
| 변경 처리: 최종값이 요약에 있음 | 88.9% (8/9) | changes 기준 |
| 변경 처리: 폐기값만 요약에 남음 | 11.1% (1/9) | 오류 |
| 변경 처리: 폐기 날짜가 availability에 잔존 | 50.0% (1/2) | 합집합 병합 한계 |
| PII 누출 (건) — 단독 / 스크러버 후 | 0.0% (0/14) / 0.0% (0/14) | |

케이스별 슬롯 보존 (date/place/purpose):

- availability_constraint: date 1/1, place 1/1, purpose 1/1
- budget: date 1/1, place 1/1, purpose 1/1
- feedback_conflict: date 1/1, place 1/1, purpose 1/1
- location_change: date 1/1, place 1/1, purpose 1/1
- multi_change_long: date 1/1, place 1/1, purpose 1/1
- negation: date 1/1, place 1/1, purpose 1/1
- opinion_conflict: date 1/1, place 1/1, purpose 1/1
- pii_third_party: date 1/1, place 1/1, purpose 1/1
- place_missing: date 1/1, purpose 1/1
- preference_conflict: date 1/1, place 1/1, purpose 1/1
- quiet_pref_noisy_dislike: date 1/1, place 1/1, purpose 1/1
- schedule_change: date 1/1, place 1/1, purpose 1/1
- time_change_headcount: date 1/1, place 1/1, purpose 1/1
- weather_contingency: date 1/1, place 1/1, purpose 1/1