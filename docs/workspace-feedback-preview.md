# 작업실 OpenAI 피드백 미리보기

2026-10-13 · 2단계 서버 구현 · 사용자 선택 모델: `gpt-4o-mini`

`POST /api/layouts/feedback/preview`는 브라우저의 최신 가구 배열로 변경 후보를 만든다.
Room/AgentContext/Layout을 저장하지 않는다. 기존 `/api/layouts/feedback` API는 그대로 유지한다.
작업실 버튼 연결, Web 모델·색상 병합과 최종 공간 검증은 다음 단계다.
환경변수만 등록해도 아직 연결하지 않은 작업실 버튼이 작동하는 것은 아니다.

## 키 발급과 서버 설정

1. [OpenAI API Keys](https://platform.openai.com/api-keys)에 로그인한다.
2. 사용할 프로젝트에서 새 비밀 키를 생성하고 안전하게 보관한다.
3. 키는 아래 서버 secret에 넣는다. 채팅, Git, Web의 `VITE_*`, localStorage에 넣지 않는다.

OpenAI 공식 문서는 서버 환경변수로 키를 주입하는 방식을 안내하며,
`gpt-4o-mini`는 Chat Completions와 Structured Outputs를 지원한다.
[공식 시작 안내](https://developers.openai.com/api/docs/quickstart),
[모델 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini),
[구조화 응답](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat).

| 환경변수 | 설정값 |
| --- | --- |
| `ROOMFIT_LLM_FEEDBACK_API_KEY` | 발급받은 비밀 키 |
| `ROOMFIT_LLM_FEEDBACK_ENABLED` | `true` |
| `ROOMFIT_LLM_FEEDBACK_BASE_URL` | `https://api.openai.com/v1` |
| `ROOMFIT_LLM_FEEDBACK_MODEL` | `gpt-4o-mini` |
| `ROOMFIT_LLM_FEEDBACK_TIMEOUT_MS` | `15000` |

새 미리보기는 HTTPS OpenAI 공식 주소만 사용한다. 키·모델·주소가 없거나 활성화가 false이면
503 `FEEDBACK_LLM_NOT_CONFIGURED`를 반환하며 외부 호출은 하지 않는다.
피드백 전용 설정을 사용하므로 기존 배치 추천의 키나 활성화 설정을 바꿀 필요가 없다.

### 이 Mac에서 로컬 실행

`RoomFit-Backend` 폴더의 zsh 터미널에서 실행한다. 첫 명령에서 키를 붙여넣고 Enter를 누른다.
입력은 화면에 표시되지 않으며 명령 기록에 키를 직접 쓰지 않는다.

```zsh
read -r -s 'ROOMFIT_LLM_FEEDBACK_API_KEY?OpenAI API 키: '
export ROOMFIT_LLM_FEEDBACK_API_KEY
export ROOMFIT_LLM_FEEDBACK_ENABLED=true
export ROOMFIT_LLM_FEEDBACK_BASE_URL=https://api.openai.com/v1
export ROOMFIT_LLM_FEEDBACK_MODEL=gpt-4o-mini
export ROOMFIT_LLM_FEEDBACK_TIMEOUT_MS=15000
./gradlew -Dorg.gradle.java.home=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home --no-daemon bootRun
```

Java 21을 이미 기본으로 사용하는 다른 환경에서는 `./gradlew bootRun`이면 된다.
기본 서버 포트는 현재 `application.yml` 기준 10000이다. 같은 터미널에서 서버를 실행해야
이 환경변수를 받으며, 설정을 바꿨다면 서버를 재시작한다. `.env` 파일은 자동으로 읽지 않는다.

### 현재 EC2 배포

Backend 저장소 `.github/workflows/ci.yml`은 main의 이미지를 EC2 Docker에 배포한다.
이번 변경은 다음 GitHub Actions 값만 원격 셸과 컨테이너에 전달한다.
저장소 **Settings → Secrets and variables → Actions**에 등록한다.

- **Secrets:** `ROOMFIT_LLM_FEEDBACK_API_KEY`
- **Variables:** 나머지 네 항목을 위 표의 값으로 등록

변수 등록만으로 실행 중인 컨테이너가 변경되지는 않는다. 이 코드가 main에 반영된 후
해당 버전의 CI 배포가 완료되어야 한다. 이미 해당 버전이 main에 있다면 Actions의 CI 실행을
다시 실행해 컨테이너를 교체할 수 있다. 키를 출력하는 명령이나 Docker 환경 전체 출력으로 확인하지 않는다.
등록값이 없을 때는 false/빈 설정으로 실행하여 기존 규칙 기반 기능을 유지한다.
2026-10-13 GitHub Secret 등록과 네 Variables의 설정값을 확인했다. 기존 `808cd9d` 배포는
워크플로만 변경했으며 새 미리보기 서버 코드는 포함하지 않았다. 이번 서버 코드 배포는
사용자가 요청했으며, 실제 호출 성공 여부는 배포 후 별도로 검증한다.

## 미리보기 API

아래는 기본 Sample Room 치수에 맞춘 테스트 snapshot이다. 실제 요청은
`GET /api/rooms/{roomId}`의 치수와 **최신 브라우저 가구 배열**을 사용한다.
`X-RoomFit-Client-Id`는 기존 방 접근 권한 규칙을 그대로 따른다.
방 치수가 서버와 다르면 409 `FEEDBACK_ROOM_CHANGED`로 거부한다. 벽과 문·창은 서버 Room에서 읽는다.

```json
{
  "roomId": 1,
  "revision": "draft-1",
  "feedback": "책상을 방 기준 오른쪽으로 조금 옮겨줘",
  "roomDimensions": {"width": 5.8, "depth": 5.4, "height": 2.7},
  "furniture": [{
    "id": "preview-desk", "type": "desk", "label": "책상",
    "width": 1.2, "depth": 0.6, "height": 0.72,
    "position": {"x": 2, "z": 2}, "rotation": 0, "status": "EXISTING",
    "productId": "desk-compact-01", "variantId": "desk-compact", "styleTags": ["minimal"]
  }],
  "lockedFurnitureIds": [],
  "selectedFurnitureId": null,
  "preferences": {"purpose": "업무 / 공부", "style": "minimal", "palette": "auto"}
}
```

좌표와 크기는 meter, 회전은 degree, 위치는 기존 서버의 방 모서리 원점 기준이다.
Web의 중앙 원점/radian을 그대로 전송하면 안 된다. 기존 Web 변환기를 후속 연결에서 재사용한다.
활성 가구 최대 12개, 전체 배열 최대 24개, 피드백 최대 2000자, 연산 최대 4개다.
중복 ID, 존재하지 않는 고정·선택 ID, 음수/0/유한하지 않은 크기를 호출 전에 거부한다.

응답은 기존 `CommonResponse`의 `data` 안에 `revision`, `status`, `source`, `reasonCode`,
`summary`, `furniture`, `operationResults`, `clarification`, `validationResult`를 담는다.
성공은 **검증한 후보 생성**이며 적용·저장·최종 확정이 아니다. 실패/재질문 시 원본 가구를 반환한다.
출처는 `LLM`이며 호출 실패를 규칙 결과로 바꿔 성공 처리하지 않는다.

## 이번 연결의 지원 범위

기존 실행기의 여섯 연산을 우선 연결한다.

| 연산 | 처리 |
| --- | --- |
| 이동 | 선택한 대상의 위치만 변경. 방 기준 방향·창문/벽 근처·가구 상대 위치 등의 의미를 서버 좌표로 계산 |
| 회전 | 90°/180°/벽 정렬. 위치를 함께 옮겨야 하는 후보는 거부 |
| 상품 교체 | 같은 가구 종류 안에서 실제 상품 치수·태그로 교체. 위치·방향은 유지 |
| 추가 | 명시적으로 추가를 요청했을 때만 카탈로그에서 생성 |
| 삭제 | 명시한 대상만 삭제 |
| 복합 요청 | 최대 네 연산. 하나라도 실패하면 전체 후보 폐기 |

미연결: 직접 치수 보정, 가구별 재색칠, 추상적인 전체 재설계/최적화, 정확한 이동 거리,
임의 각도 회전, 이전 요청에 묶인 `clarificationAnswer` 재전송이다.
지원하지 않는 요청은 명확한 사유로 반환하며 다른 작업으로 임의 변환하지 않는다.
방향이나 대상이 모호하면 질문과 후보 ID를 반환한다. 재질문 UI는 다음 단계에서 연결한다.

잠금은 상품·크기·위치·방향을 보호하며, 명시적 유지 조건도 후보와 비교한다.
요청하지 않은 가구는 그대로 유지한다. 이동에 회전이 섞이거나 교체에 위치 이동이 섞이면
`UNREQUESTED_CHANGE`로 거부한다. `lamp` 같은 기존 응답 alias는 새 미리보기에서 canonical type으로 변환한다.

구조화 응답에는 좌표·상품 ID·검증 점수를 허용하지 않는다. 닫힌 JSON 스키마를 서버에서 다시
검증하고, 거절/불완전 응답/잘못된 JSON/제공자 오류는 502 `FEEDBACK_LLM_FAILED`로 처리한다.
`gpt-4o-mini`에는 system 규칙과 user snapshot을 구분하여 전송한다.
요청당 한 번 호출, 출력 최대 4096 token이며 자동 재시도는 없다.
공개 파일럿 API의 호출은 서버 프로세스당 분당 20회, 동시 2회로 제한하며 초과하면
429 `FEEDBACK_RATE_LIMITED`를 반환한다. 재시작 시 카운터는 초기화되며 계정 전체 비용 상한은 아니다.

현재 서버 검증은 본체 충돌, 방 경계, 문·창 여유, 기존 통로 근사 규칙이다.
새 작업실의 창문 높이·모델 외곽·개폐 공간·60 cm 연결 통로 검증과 완전히 같지는 않다.
그 검증과 Web 색상/모델 보존은 화면 연결 단계에서 추가한다. 전체 해석 명세의 96개 사례가
모두 구현되거나 실제 LLM 정확도로 검증된 것은 아니다.

## 배포 전 검증 기록

- 호출 제한을 포함한 새 기능과 기존 해석기·실행기·제공자 설정 회귀 테스트 13개 클래스, 192개 테스트가 통과했다.
- 모의 OpenAI 응답, 닫힌 계획 스키마, 잠금·유지, 복합 롤백, 원본 보존, 권한·입력 오류를 검사했다.
- Spring MVC/H2에서 Room 가구와 Room/Layout/Context 행 수가 변경되지 않음을 확인했다.
- 배포 YAML을 파싱하고 다섯 변수의 원격 전달·Docker 전달과 키의 GitHub Secret 참조를 확인했다.
- 실제 키는 사용하지 않았다. 실제 모델의 해석 품질·응답 시간·비용, 화면 연결과 배포는 미검증이다.
- GitHub 환경변수 전달 코드는 로컬에서 검토했으며 실제 Actions 배포는 실행하지 않았다.
