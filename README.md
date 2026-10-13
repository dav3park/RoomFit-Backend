# RoomFit Backend

RoomFit의 방 구조, 사용자 취향, 가구 상품을 바탕으로 레이아웃을 추천하고 자연어 피드백을 안전하게 반영하는 Spring Boot API입니다.

## 핵심 흐름

```text
RoomPlan 데이터 업로드
  → 사용자 컨텍스트·상품 선택
  → 규칙 기반 레이아웃 추천
  → LLM 또는 규칙 기반 피드백 해석
  → 결정론적 수정·배치 검증
  → Web 편집기 렌더링 및 확정
```

LLM은 자연어를 구조화된 피드백 계획으로 해석하는 보조 역할입니다. 가구 좌표, 회전, 최종 검증 결과를 LLM이 직접 확정하지 않으며, 실제 변경은 서버의 허용 연산·대상 확인·배치 검증을 통과해야 합니다.

## 주요 기능

- RoomPlan 기반 방 구조 업로드 및 샘플 방 조회
- 라이프스타일, 스타일, 필수 가구, 선택 상품을 포함한 Agent Context 생성
- 공간 크기·상품 치수·스타일·동선·문/창문 여유 공간을 고려한 레이아웃 추천
- 자연어 피드백의 LLM 해석과 규칙 기반 fallback
- 중복 대상은 후보 선택을 요구하고, 복합 명령은 원자적으로 적용
- LEFT/RIGHT·corner·상품 추가/실패 등 피드백 시나리오의 결정론적 처리
- 충돌, 방 경계, 문/창문 여유 공간, 이동 경로 검증
- 최신 `layoutId`를 기준으로 수정·새로고침·확정 흐름을 유지하는 API

## API

| Method | Endpoint | 설명 |
|---|---|---|
| `GET` | `/health` | 헬스 체크 |
| `POST` | `/api/rooms/upload` | RoomPlan 방 데이터 업로드 |
| `GET` | `/api/rooms/{roomId}` | 방 데이터 조회 |
| `GET` | `/api/rooms/samples` | 샘플 방 조회 |
| `GET` | `/api/products/mock` | 목업 가구 상품 조회 |
| `POST` | `/api/agent/context` | 사용자 선호 컨텍스트 생성 |
| `POST` | `/api/layouts/recommend` | 레이아웃 추천 |
| `POST` | `/api/layouts/validate` | 수정된 레이아웃 검증 |
| `PUT` | `/api/layouts/{layoutId}` | 레이아웃 저장/수정 |
| `POST` | `/api/layouts/feedback` | 자연어 피드백 적용 |
| `POST` | `/api/layouts/{layoutId}/confirm` | 최종 레이아웃 확정 |

세부 스키마와 예시는 실행 중인 서버의 Swagger UI 또는 [`docs/openapi/roomfit-api.yaml`](docs/openapi/roomfit-api.yaml)을 참조하세요.

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Build | Gradle Wrapper |
| Persistence | Spring Data JPA, H2(local/test), PostgreSQL(production) |
| API 문서 | springdoc OpenAPI / Swagger UI |
| LLM 연동 | OpenAI-compatible Chat Completions API |

## 로컬 실행

요구 사항은 Java 21입니다. Gradle은 Wrapper를 사용하므로 별도 설치가 필요하지 않습니다.

```bash
./gradlew bootRun
```

기본 주소는 `http://localhost:8080`이며, 다음으로 확인할 수 있습니다.

```bash
curl -i http://localhost:8080/health
```

## 검증

```bash
./gradlew test
./gradlew clean build
```

일반 테스트는 외부 LLM 호출을 하지 않습니다. 실제 LLM 의미 검증은 명시적으로 opt-in한 비프로덕션 환경에서만 실행합니다.

```bash
export ROOMFIT_LLM_FEEDBACK_ENABLED=true
export ROOMFIT_LLM_BASE_URL=https://api.openai.com/v1
export ROOMFIT_LLM_MODEL=<model-id>
export ROOMFIT_LLM_API_KEY=<secret>
./gradlew realLlmEvaluation
```

실행 전용 API 키는 셸 또는 CI secret으로 주입하고, 출력·커밋·문서 기록을 금지합니다. 이 검증은 Production Backend나 Production DB에 연결하지 않아야 합니다.

## 환경변수

LLM 기능은 선택 사항입니다. 설정하지 않아도 규칙 기반 추천과 피드백 처리로 서버를 실행할 수 있습니다.

| 변수 | 설명 |
|---|---|
| `ROOMFIT_LLM_FEEDBACK_ENABLED` | LLM 피드백 해석 활성화 |
| `ROOMFIT_LLM_PLACEMENT_ENABLED` | 선택적 LLM 배치 제안 활성화 |
| `ROOMFIT_LLM_API_KEY` | LLM 제공자 API 키 — secret으로만 주입 |
| `ROOMFIT_LLM_BASE_URL` | OpenAI 호환 Chat Completions base URL |
| `ROOMFIT_LLM_MODEL` | 모델 ID |
| `ROOMFIT_LLM_TIMEOUT_MS` | LLM 호출 제한 시간(ms) |
| `SPRING_PROFILES_ACTIVE` | Production에서는 `prod` |
| `SPRING_DATASOURCE_URL` | Production PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Production DB 자격 증명 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Production 스키마 설정 |

`prod` 프로필은 H2 fallback을 허용하지 않습니다. Production에서는 PostgreSQL JDBC URL과 자격 증명을 모두 제공해야 하며, 값은 저장소에 커밋하지 않습니다.

## 운영 및 개발 문서

- [작업실 OpenAI 피드백 설정·미리보기 API](docs/workspace-feedback-preview.md)
- [Frontend API 연동 가이드](docs/frontend-api-integration.md)
- [LLM 피드백 평가 가이드](docs/llm-feedback-evaluation.md)
- [OpenAPI 명세](docs/openapi/roomfit-api.yaml)
- [배포 후 smoke test](scripts/smoke-test.sh)

## 현재 범위

상품 카탈로그는 목업 데이터이며, 추천은 설명 가능한 규칙 기반 결과를 우선합니다. LLM 장애·시간 초과·안전하지 않은 해석에서는 fallback 또는 명확화 응답을 반환해 현재 레이아웃을 보존합니다.
