# 평가 스크립트 사용법

원문이 담긴 산출물은 모두 `.eval-local/`(git 제외)에 생성된다. 저장소에는 스크립트·집계·ID만 둔다.

## 1. 평가셋 생성 (PC)

```
python scripts/eval/build_evalset.py            # AI Hub ZIP → candidates / bulk_ondevice / scrubber_bulk
python scripts/eval/synthetic_dialogues.py      # 합성 대화(정답 동봉) → synthetic.json
python scripts/eval/feedback_corpus.py          # 후기 40 + 질의 200 → feedback_corpus.json
```

## 2. 스크러버 대량 검증 (JVM, 모델 불필요)

```
./gradlew.bat :app:testDebugUnitTest --tests "com.navoodi.morimi.service.PiiScrubberBulkEvalTest" --rerun
# → app/build/eval/scrubber_bulk_report.json
```

## 3. 실기기 실행기 (androidTest, 판정 없음 — JSONL 기록)

```
adb push .eval-local/evalset/bulk_ondevice.json  /sdcard/Android/data/com.navoodi.morimi/files/models/
adb push .eval-local/evalset/synthetic.json      /sdcard/Android/data/com.navoodi.morimi/files/models/
adb push .eval-local/evalset/feedback_corpus.json /sdcard/Android/data/com.navoodi.morimi/files/models/
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

R=com.navoodi.morimi.test/androidx.test.runner.AndroidJUnitRunner
# 온디바이스 요약·압축 (대량셋 315건, 약 50분)
adb shell am instrument -w -e class com.navoodi.morimi.eval.OnDeviceEvalRunner $R
# 합성셋 3회 반복
adb shell am instrument -w -e class com.navoodi.morimi.eval.OnDeviceEvalRunner -e evalFile synthetic.json -e reps 3 $R
# 후기 검색 키워드 vs 시맨틱
adb shell am instrument -w -e class com.navoodi.morimi.eval.RetrievalEvalRunner $R
# 추천 전체 (Firebase 로그인 필요, 시나리오 × 조건 3)
adb shell am instrument -w -e class com.navoodi.morimi.eval.RecommendationEvalRunner -e conditions none,keyword,semantic $R

adb pull /sdcard/Android/data/com.navoodi.morimi/files/eval/ .eval-local/results/
```

Git Bash에서는 `/sdcard/...` 경로가 변환되므로 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.
실행 중 APK를 재설치하면 실행이 죽는다. 결과는 한 줄씩 flush 되므로 중단돼도 부분 결과가 남는다.

## 4. 채점 (PC)

```
python scripts/eval/score_ondevice.py  .eval-local/results/ondevice_bulk_ondevice_<시각>.jsonl --out docs/evidence/<날짜>/eval
python scripts/eval/score_slots.py     .eval-local/results/ondevice_synthetic_<시각>.jsonl --evalset .eval-local/evalset/synthetic.json --out docs/evidence/<날짜>/eval
python scripts/eval/score_retrieval.py .eval-local/results/retrieval_<시각>.jsonl --out docs/evidence/<날짜>/eval
python scripts/eval/score_recommend.py .eval-local/results/recommend_synthetic_<시각>.jsonl --out docs/evidence/<날짜>/eval
```

집계 결과는 `docs/eval/RESULTS.md`에 조건·표본 수와 함께 옮긴다.
