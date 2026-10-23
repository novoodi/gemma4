# R2 — v4 → v6 마이그레이션 실검증 (에뮬레이터)

> **2026-09-23** · 에뮬레이터 API 36 google_apis x86_64 (AVD `moimi_r2`)
> **실기기가 아니다.** 실제 사용자 기기의 데이터 분포는 재현하지 않았다.

## 무엇을 했나

Robolectric 검증(`MigrationRuntimeTest`)은 JVM 위에서 돈다. 여기서는 **실제 Android 런타임**에서
같은 경로를 밟았다 — 실기기 R2에 가장 가까운 검증이다.

1. v4 스키마 DB를 호스트에서 만들어(방 3개·후기 5건·임베딩 BLOB 2건·날짜 형식 혼합)
   `PRAGMA user_version = 4`로 설정
2. 앱을 설치해 데이터 디렉터리를 만들고, 기존 DB를 지운 뒤 v4 DB를 심었다
   (`adb root` + push + chown u0_a216)
3. 앱 실행 → `MoimApp.onCreate` → `reindexFeedbackEmbeddings` → **DB가 열리며 마이그레이션 실행**
   (로그인 전에 열린다 — 그래서 로그인 없이도 이 경로를 검증할 수 있었다)

## 결과 — 전부 통과

| 확인 항목 | 결과 |
|---|---|
| 크래시 | **0건** (앱 프로세스 18초 이상 생존) |
| `user_version` | 4 → **6** |
| 테이블 | `harness_run`·`ahp_judgment` 신설, 기존 4개 유지 |
| `harness_run` 컬럼 | **21개** (v6 신규 5개 포함) |
| Room identity | `room_master_table`에 새로 기록 (길이 32) |

### 데이터 생존 — R2의 진짜 완료 기준

| 테이블 | 시드 | 마이그레이션 후 |
|---|---|---|
| `user_status` | 2 | **2** |
| `feedback` | 5 | **5** |
| `recommended_room` | 2 | **2** |
| `meeting_summary` | 1 | **1** |

```
id | roomId | date       | rating | createdAt     | 본문
 1 | room-A | 2026-08-01 | 0      | 1785542400000 | 조용해서 대화하기 좋았어요
 2 | room-A | 2026-08-15 | 0      | 1786752000000 | 사람이 너무 많아 시끄러웠다
 3 | room-B | 2026-09-02 | 0      | 1788307200000 | 파스타 맛집 재방문 의사 있음
 4 | room-B | 언젠가      | 0      | 0             | 날짜 형식이 다른 행 - 백필 대상 아님
 5 | room-C | 2026-09-10 | 0      | 1788998400000 | 가격이 좀 비쌌다
```

- **`rating`은 전부 0** — v5 신규 컬럼의 기본값이 제대로 들어갔다
- **`createdAt` 백필이 정확하다** — 4건의 epoch ms를 날짜로 되돌려 검산했고 전부 일치
- **형식이 다른 행(id=4)은 `createdAt=0`** — `LIKE '____-__-__'` 조건대로 백필하지 않았다.
  의도된 동작이고, 그런 방은 후기 팝업에서 폴백 경로를 탄다
- **임베딩 BLOB 보존** — id 2·5의 64바이트가 그대로

### 재시작 안정성

앱을 강제 종료하고 다시 띄웠다. **Room이 v6 스키마를 재검사하는 경로**다.

- 크래시 0건, 후기 5건 유지, `user_version = 6`
- **WAL 파일이 실제로 생겼다** (`-wal` 8,272바이트 · `-shm` 32,768바이트).
  Robolectric에서는 재현되지 않던 조건이다

## 여전히 못 한 것

- **실기기가 아니다.** 실제 사용자의 v4 DB는 데이터 양·분포·WAL 상태가 다를 수 있다.
- **R3~R7(로그인 이후 흐름)은 막혀 있다.** `google-services.json`이 placeholder라
  Firebase 인증이 안 되고, 프록시도 로그인 사용자만 허용한다.
  → 정품 설정 파일 + `firebase deploy --only functions` 이후에 가능하다.

## 재현 방법

```bash
# 1) v4 DB 생성 (scripts 없음 — 아래 DDL은 MigrationRuntimeTest와 동일)
# 2) 에뮬레이터에 심기
adb root
adb push moim_database_v4.db /data/data/com.navoodi.morimi/databases/moim_database
adb shell chown u0_a216:u0_a216 /data/data/com.navoodi.morimi/databases/moim_database
adb shell chmod 660 /data/data/com.navoodi.morimi/databases/moim_database
# 3) 앱 실행 → 마이그레이션
adb shell am start -n com.navoodi.morimi/.MainActivity
# 4) 확인
adb shell "sqlite3 /data/data/com.navoodi.morimi/databases/moim_database 'PRAGMA user_version;'"
```

> ⚠️ `adb root`는 `google_apis` 이미지에서만 된다(Play Store 이미지는 불가).
> ⚠️ **물리 기기가 연결된 상태로 하지 말 것** — 실제 사용자 DB를 덮어쓴다.
