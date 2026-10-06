# 카카오맵 JavaScript 키 설정 (추천 결과 지도)

추천 결과 화면(`AIReportScreen`) 상단 지도는 WebView + 카카오맵 JavaScript SDK로 그린다.
키가 없어도 앱은 정상 동작한다. 이때 지도 자리에 "지도를 불러올 수 없음 (지도 키 미설정)"과
장소별 주소·카카오맵 링크 목록이 대신 나온다(링크는 키 없이 열림).

## 1. 키 발급

1. https://developers.kakao.com 로그인 → **내 애플리케이션** → 기존 앱 선택
   - 장소 검색용 REST 키를 받은 앱을 그대로 써도 된다. 같은 앱에 JavaScript 키가 함께 발급돼 있다
2. **앱 설정 > 앱 키**에서 **JavaScript 키**를 복사한다
   - REST API 키가 아니다. REST 키는 계속 Firebase Functions 시크릿에만 둔다
3. **앱 설정 > 플랫폼 > Web > 사이트 도메인**에 `http://localhost`를 등록한다
   - 앱은 지도 HTML을 이 출처(origin)로 띄운다. 등록하지 않으면 카카오가 SDK 요청을 거절해서 "지도를 불러올 수 없음"으로 대체된다
4. **제품 설정 > 카카오맵**이 사용 설정(ON)인지 확인한다. 꺼져 있으면 지도 타일이 나오지 않는다

## 2. 프로젝트에 넣기

프로젝트 루트의 `local.properties`(git 제외 파일)에 추가한다.

```properties
kakao.map.js.key=여기에_JavaScript_키_32자
# 선택: 콘솔에 다른 도메인을 등록했다면 같은 값으로 (기본값 http://localhost)
# kakao.map.origin=http://localhost
```

빌드하면 `BuildConfig.KAKAO_MAP_JS_KEY`, `BuildConfig.KAKAO_MAP_ORIGIN`으로 들어간다.
값을 바꾼 뒤에는 Gradle Sync 또는 Rebuild가 필요하다.

## 3. 확인

| 화면 | 의미 |
|---|---|
| 지도에 번호 핀이 보임 | 정상. 핀 번호 = 장소 카드 순서. 카드를 넘기면 해당 핀이 강조된다 |
| "지도를 불러올 수 없음 (지도 키 미설정)" | `local.properties`에 키가 없거나 형식이 다름(영숫자 16~64자만 허용) |
| "지도를 불러올 수 없음" | 키는 있으나 SDK 로드 실패 또는 10초 초과 — 도메인 등록, 카카오맵 사용 설정, 네트워크 확인 |
| "지도에 표시할 위치 정보가 없습니다" | 추천 장소에 좌표가 하나도 없음(Gemini가 searchPlace 도구를 쓰지 않았거나 매칭 실패) |

## 왜 이 키는 APK에 들어가도 되는가

CLAUDE.md 원칙상 외부 API 키는 APK에 넣지 않는다. JavaScript 키는 예외인데, 이유는 다음과 같다.

- 브라우저에 노출되도록 설계된 **클라이언트 키**이고, 콘솔에 등록한 도메인에서만 동작한다
- 그래서 장소 검색용 REST 키와 성격이 다르다

그래도 저장소에는 올리지 않는다. 원칙의 허용 경로인 `local.properties` → `BuildConfig`로만 넣는다.
