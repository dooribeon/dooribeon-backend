# 두리번 백엔드

예산과 생활거점까지의 이동 부담을 비교해 거주할 동네 후보를 찾는 Spring 서버입니다.

Java 21 · Spring Boot 4.1.2 · Gradle. 이 문서는 현재 로컬 코드 기준입니다. 추천 규칙 전체의 팀 합의나 GitHub main 반영 완료를 뜻하지 않습니다.

## 현재 구현과 연결 범위

- **AI 조건 정리:** ChatKHU로 조건 추출 → 서버 검사·추가 질문 → 답변 병합 → 공통 `conditions` 반환.
- **장소 위치:** 카카오 검색 결과에서 사용자가 선택한 위치를 반영합니다. AI가 좌표를 만들지 않습니다.
- **단일 동네 검사:** 같은 계약의 예산 충족 여부와 필수 장소의 이동시간 제한을 확인합니다.
- **기존 추천 API:** 동료의 `POST /api/recommend`는 아직 기존 `RecommendCondition` 형식입니다. 공통 JSON 적용과 실제 채팅 결과를 이용한 통합 확인은 후속 작업입니다.
- **전달 예제:** 완성된 채팅 응답에서 `conditions`만 꺼내 추천 API로 보내는 Python 예제입니다. 실행 예제 준비와 실제 추천 연결 성공은 구분합니다.

## 실행과 키 설정

1. JDK 21을 준비하고 IntelliJ에서 이 폴더를 엽니다. Lombok을 위해 Annotation Processing을 활성화합니다.
2. `DooribeonBackendApplication`의 **Run → Edit Configurations → Environment variables**에 아래 변수를 등록합니다.
3. 애플리케이션을 실행합니다. 기본 주소는 `http://localhost:8080`입니다. 코드·환경 변수를 바꾼 뒤에는 서버를 재시작합니다.

| 환경 변수 | 용도 |
| --- | --- |
| `CHATKHU_API_KEY` | ChatKHU 인증 키 |
| `CHATKHU_MODEL` | 모델 ID. 현재 설정의 기본값은 `claude-haiku-5-5` |
| `CHATKHU_BASE_URL` | 선택 설정. 기본값은 `https://factchat-cloud.mindlogic.ai/v1/gateway` |
| `KAKAO_REST_API_KEY` | 장소 검색과 경로 조회에 함께 사용하는 카카오 REST API 키 |

카카오 앱에서 해당 API 사용 권한이 활성화되어 있어야 합니다. 키가 없어도 서버와 예산만 있는 동네 검사는 실행되지만 AI·장소·경로 API를 이용할 때는 각 키가 필요합니다.

키는 환경 변수로 관리합니다. `.env` 자동 로딩 기능은 없습니다. `.idea/`, `.env` 계열, 개인 `application-local.yml`·`.yaml`·`.properties`는 Git 제외 대상입니다. `application-local.example.yml`은 기존 경로 키 설정 예시이며, 위 환경 변수 방식은 장소 검색에도 함께 적용됩니다.

터미널에서는 환경 변수를 설정한 뒤 실행합니다.

```sh
./gradlew bootRun
```

서버 상태 확인은 외부 API를 호출하지 않습니다.

```sh
curl http://localhost:8080/api/health
```

기대 응답: `{"status":"UP"}`.

## 공통 조건과 응답

보증금 `maxDeposit`·월세 `maxRent`는 **0 이상 정수 만원이며 둘 다 필수**입니다. 미입력 `null`과 명시적인 `0`은 다릅니다. 전세는 지원하지 않고 관리비는 제외합니다. 채팅에서 단위 없는 금액은 확인하며, 만원 미만 금액을 임의로 반올림하지 않습니다.

장소는 없어도 됩니다. 특정 장소가 있으면 검색·선택한 `location`이 필요합니다. `REQUIRED` 장소에는 이동수단과 양의 정수 최대 편도시간도 필요합니다. 회사·학교 등 업무·학교 장소는 중요도를 따로 정하지 않았을 때 서버 기본값으로 REQUIRED를 적용하고 안내합니다.

다음은 금액이 일부 빠진 **대화 응답의 설명용 예시**입니다. 실제 대화 번호는 매번 달라집니다.

```json
{
  "conversationId": "00000000-0000-4000-8000-000000000001",
  "conditions": {"maxDeposit": null, "maxRent": 50, "places": []},
  "readyForRecommendation": false,
  "issues": [{"path": "maxDeposit", "kind": "MISSING", "message": "보증금은 최대 몇 만원까지 생각하고 있나요?"}],
  "nextQuestion": "보증금은 최대 몇 만원까지 생각하고 있나요?",
  "notices": [],
  "deletionConfirmation": null
}
```

- 화면에는 `nextQuestion`을 보여주고 같은 대화 번호로 답변을 보냅니다. `issues`는 남은 문제, `notices`는 기본값·미반영 등의 안내입니다.
- `readyForRecommendation: true`는 입력 준비 완료입니다. 실제 경로·계약 데이터·추천 결과의 존재나 추천 자동 실행을 보장하지 않습니다.
- 장소에는 `id`, 선택 표시명 `label`, `targetType`, 검색 표현 `query`, 선택 위치 `location`, `importance`, `transport`, `maxMinutes`, `frequency`가 있습니다. 위치는 장소명·주소·위도 `lat`·경도 `lng` 등을 담습니다.
- 중요도는 `REQUIRED/HIGH/MEDIUM/LOW/null`, 이동수단은 `PUBLIC_TRANSIT/WALK/null`입니다. 필수가 아닌 장소의 수단이 비어 있으면 계산 시 대중교통을 적용한다고 안내합니다.
- 빈도는 선택 입력입니다. 예: `{"period":"MONTH","count":2}`. 주/월 구분인 WEEK/MONTH와 정수 횟수를 보존합니다. 계산 활용·주당 환산은 미확정입니다.
- `NEARBY_FACILITY`처럼 주변 대체 시설을 찾는 조건은 아직 추천 미지원으로 안내합니다. 일반 선호조건 목록이나 임의의 좌표를 만들지 않습니다.

## API

| 요청 | 역할·외부 호출 |
| --- | --- |
| `GET /api/health` | 서버 상태. 외부 호출 없음 |
| `POST /api/conditions/parse` | 독립 문장 추출. ChatKHU 호출 |
| `POST /api/conditions/conversations` | 대화 시작. ChatKHU 호출 |
| `POST /api/conditions/conversations/{conversationId}/messages` | 추가 답변·수정. 일반적으로 ChatKHU 호출 |
| `POST /api/conditions/conversations/{conversationId}/places/{placeId}/search` | 카카오 장소 검색 |
| `PUT /api/conditions/conversations/{conversationId}/places/{placeId}/location` | 보관 중인 후보 선택. 외부 호출 없음 |
| `POST /api/recommend/check?neighborhoodKey=구·동` | 공통 JSON으로 동네 한 곳 검사. 장소별 경로 조회 가능 |
| `POST /api/recommend` | 기존 형식의 동료 추천 API. 여러 경로 호출 가능. 공통 JSON 전환은 후속 작업 |

추출·대화 요청은 `{"text":"사용자 문장"}`이며 공백이 아닌 최대 10,000자 문자열입니다. `/parse` 응답에는 대화 번호·삭제 확인 필드가 없고 대화 상태도 저장하지 않습니다.

### 추가 질문에 답하기

첫 요청은 월세만 입력해 보증금 질문을 받는 예입니다. 두 요청은 각각 ChatKHU 사용량이 발생할 수 있습니다.

```sh
curl -X POST http://localhost:8080/api/conditions/conversations \
  -H 'Content-Type: application/json' \
  -d '{"text":"월세는 50만 원 이하로 찾고 있어."}'
```

반환된 `conversationId`를 아래 `대화번호`에 넣습니다.

```sh
curl -X POST http://localhost:8080/api/conditions/conversations/대화번호/messages \
  -H 'Content-Type: application/json' \
  -d '{"text":"보증금은 천만 원까지야."}'
```

예산만 있는 경우 기대 조건은 `maxDeposit: 1000`, `maxRent: 50`, `places: []`이며 준비 여부는 true입니다. AI의 문장 해석과 서버 검사는 구분해서 확인합니다.

서버는 기존 조건·직전 질문·새 답변을 AI에 보내고 반환된 변경분만 병합합니다. 미언급 값은 유지하고 명시적인 비우기는 `null`로 처리합니다. 호출·응답 검사 실패 때는 기존 대화 조건을 유지합니다.

### 장소 검색과 선택

장소가 있는 대화를 시작한 뒤 응답의 `places[].id`를 사용합니다. 이 번호는 카카오 장소 번호와 다릅니다.

1. `/search`에 `{"query":"여의도역","type":"KEYWORD","page":1}`을 보냅니다. 주소 검색은 `type: "ADDRESS"`입니다.
2. 반환된 `searchId`와 선택할 후보의 `candidateId`를 `/location`에 보냅니다.
3. 서버가 선택 위치를 반영하고 조건을 다시 검사합니다. 검색 결과 첫 항목을 자동으로 확정하지 않습니다.

검색은 페이지 1~45, 한 페이지 최대 15개입니다. 한 대화의 마지막 검색만 선택 가능하며 5분 후 만료됩니다. 조건 변경 후에도 다시 검색해야 합니다. 장소 이름만 변경·비우면 위치를 유지하고 검색 대상 위치를 바꾸면 이전 선택 위치를 비웁니다.

장소 전체 삭제는 먼저 대상 안내를 받은 뒤 응답의 `deletionConfirmation.confirmationText`를 그대로 입력합니다. `삭제 취소`로 취소할 수 있습니다. 확인 대기 중에는 준비 여부가 false입니다.

### 동네 한 곳 검사

아래 요청은 장소가 없어 외부 API를 호출하지 않습니다. URL의 인코딩된 동네 이름은 `관악구 봉천동`입니다.

```sh
curl -X POST \
  'http://localhost:8080/api/recommend/check?neighborhoodKey=%EA%B4%80%EC%95%85%EA%B5%AC%20%EB%B4%89%EC%B2%9C%EB%8F%99' \
  -H 'Content-Type: application/json' \
  -d '{"maxDeposit":1000,"maxRent":50,"places":[]}'
```

`conditions` 안의 내용만 본문으로 보냅니다. `rankingApplied`는 false입니다. 예산에 맞는 계약 한 건의 존재 여부를 검사하며 최소 표본·빈도·후보 축소·순위 정책은 적용하지 않습니다. 과거 계약과 현재 매물은 다르며 관리비도 제외합니다. 데이터 설명은 [data/README.md](data/README.md)를 참고합니다.

| `status` | 의미 |
| --- | --- |
| `INPUT_REQUIRED` | 입력 누락·확인 필요·잘못된 값·미지원 조건. `issues`와 `nextQuestion` 확인 |
| `NO_CONTRACT_DATA` | 동네 계약 데이터 없음 |
| `NO_BUDGET_MATCH` | 같은 계약에서 두 금액이 모두 예산에 맞는 사례 없음 |
| `REQUIRED_TIME_EXCEEDED` | 필수 장소 중 시간 제한 초과 |
| `REQUIRED_ROUTE_UNAVAILABLE` | 필수 장소의 경로를 찾지 못해 판정 불가 |
| `CHECK_PASSED` | 이 검사 기준 통과. 최종 추천 순위나 모든 선택 장소의 경로 성공을 뜻하지 않음 |

대중교통은 조회된 경로 중 가장 짧은 제공자 시간을 사용합니다. 모든 REQUIRED 장소가 제한 안에 들어야 하며 필수가 아닌 장소의 시간 초과만으로 탈락시키지 않습니다. 조회 실패는 정상적인 경로 없음이나 0분으로 처리하지 않습니다. `seconds`로 상한을 비교하고 `minutes`는 표시용 올림 값입니다. 동네 기준 지점의 시간이므로 개별 집의 이동시간과 다를 수 있습니다.

### 추천 전달 실행 예제

요청 예시는 [requests.http](requests.http)에 있습니다. [examples/send_recommendation.py](examples/send_recommendation.py)는 채팅 응답 전체를 읽어 준비 여부를 확인하고 `conditions`만 `/api/recommend`에 보냅니다. 기본 실행은 미리보기입니다.

```sh
python3 examples/send_recommendation.py
python3 examples/send_recommendation.py /tmp/dooribeon-chat-response.json
```

첫 명령은 설명용 샘플, 두 번째는 사용자가 저장한 실제 채팅 응답을 읽습니다. Python 3 기본 라이브러리만 사용합니다. **동료의 공통 JSON 적용 후** 실제 전송하려면 아래처럼 실행합니다. 추천 API의 경로 사용량이 발생할 수 있습니다.

```sh
python3 examples/send_recommendation.py /tmp/dooribeon-chat-response.json --send
```

## 임시 저장과 오류

- 대화는 단일 서버 메모리에 최대 1,000개 보관하고 마지막 성공 응답 후 30분 만료됩니다. 서버 재시작 시 사라집니다. 로그인 사용자별 영구 저장·여러 서버 간 공유는 아직 지원하지 않습니다.
- 동시에 같은 대화를 처리하면 `409 CONVERSATION_BUSY`를 반환합니다. 이전 응답을 받은 뒤 다음 요청을 보냅니다.
- 오류 형식은 `{"code":"...","message":"..."}`입니다. 입력 누락 같은 정상적인 추가 질문은 HTTP 200 응답의 `issues`로 전달될 수 있습니다.

| HTTP | 대표 오류 코드 |
| --- | --- |
| 400 | `INVALID_INPUT`, `INVALID_JSON`, `INVALID_CONVERSATION_ID`, `INVALID_PLACE_SEARCH`, `INVALID_PLACE_SELECTION` |
| 404 | `CONVERSATION_NOT_FOUND`, `PLACE_NOT_FOUND`, `NEIGHBORHOOD_NOT_FOUND` |
| 409 | `CONVERSATION_BUSY`, `PLACE_DELETE_PENDING`, `STALE_PLACE_SEARCH` |
| 502 | `AI_PROVIDER_ERROR`, `AI_INVALID_RESPONSE`, `PLACE_SEARCH_PROVIDER_ERROR`, `PLACE_SEARCH_INVALID_RESPONSE`, `ROUTE_PROVIDER_ERROR`, `ROUTE_INVALID_RESPONSE` |
| 503 | `AI_NOT_CONFIGURED`, `AI_BUSY`, `AI_CONNECTION_FAILED`, `PLACE_SEARCH_NOT_CONFIGURED`, `PLACE_SEARCH_BUSY`, `PLACE_SEARCH_CONNECTION_FAILED`, `ROUTE_NOT_CONFIGURED`, `ROUTE_PROVIDER_BUSY`, `ROUTE_PROVIDER_UNAVAILABLE`, `CONVERSATION_CAPACITY` |
| 504 | `AI_TIMEOUT`, `PLACE_SEARCH_TIMEOUT` |

## 코드에서 볼 곳

| 위치 | 역할 |
| --- | --- |
| `condition/Conditions`, `PlaceCondition` | 공통 JSON과 장소 조건 |
| `condition/ConditionsRules` | 필수값·값 범위·기본값 검사 |
| `condition/ConditionsExtractionService`, `ConditionsMerger` | 최초 추출 처리·추가 답변 병합 |
| `condition/ConditionConversationService` | 대화·질문·검색·선택·삭제 확인 보관 |
| `condition/chatkhu/ChatKhuConditionsClient`, `ChatKhuConditionsCodec` | AI 호출과 응답 검사 |
| `src/main/resources/ai/conditions-*` | 현재 AI 지침과 출력 스키마 |
| `recommend/RecommendationCheckService` | 단일 동네 예산·필수 시간 검사 |
| `route/`, `neighborhood/` | 동료 경로 기능·동네 및 계약 데이터. 경로 기능에는 이번 시간 검사 처리가 추가됨 |

과거 원 단위·전세/월세 추출용 `Budget*`, `ConditionParsingService`, `ConditionParseResponse`, `RentalType`, `ai/budget-*` 및 관련 테스트도 현재 폴더에 남아 있습니다. 현재 공개 조건 컨트롤러는 `Conditions*` 흐름을 사용합니다. 과거 파일이 있다는 이유로 전세 지원 API로 해석하지 않습니다.

## 검증

서버 구성과 이번 동네 검사 관련 테스트만 실행하려면:

```sh
./gradlew test \
  --tests 'com.dooribeon.recommend.RecommendationCheck*Tests' \
  --tests 'com.dooribeon.route.KakaoTravelTimeTests' \
  --tests 'com.dooribeon.DooribeonBackendApplicationTests'
```

자동 테스트는 가짜 외부 응답으로 실행합니다. 위 선택 대상은 총 11개입니다. 2026-10-28 공유 전 최종 확인에서는 `./gradlew test`로 현재 저장소의 자동 테스트 **105개 모두 통과**했습니다. 여기에는 과거 Budget 계열 테스트도 포함됩니다. 실제 외부 API·화면 전체 통합 검증과는 구분합니다.

2026-10-28 확인 범위:

- 사용자 제공 실행 결과로 공통 조건 추출·장소 검색과 선택·최대 편도시간 40분 → 30분 수정 시 기존 조건 유지 확인.
- 실행 중인 서버에서 예산 정상·보증금 누락·소수 금액 응답 확인.
- 승인된 실제 대중교통 조회 1회: 봉천동 기준 지점 → 여의도역 5호선 1,874초(31분 14초), 30분 제한 초과 판정. 도보·오류 등은 가짜 응답 검사와 구분합니다.
- Python 전달 예제는 가짜 수신 서버에서 JSON 보존·미완성 응답 전송 차단 확인. 실제 추천 통합은 후속 작업입니다.

추천 API의 공통 JSON 수용, 후보 축소·중요도별 순위·빈도 활용·후보 없음 안내, 실제 서비스 화면 연결은 동료와 후속 작업 범위를 맞춥니다.
