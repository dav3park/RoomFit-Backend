# 디자인 상담 분석 API

2026-10-18. `POST /api/design-consultation/analyze`, multipart/form-data.

`brief`: JSON part `{revision: string, description: string, furniture: [{id,name,type}]}`. description 최대 2,000자, 가구 최대 24개. description 또는 images 중 하나는 필요하다. `images`: 최대 3개 JPG/PNG 파일(장당 5 MiB). 웹은 업로드 전에 원본 JPG/PNG/WebP를 1,024px 이하·256 KiB 이하 JPEG로 변환한다. 임의 URL/HTML은 읽지 않는다.

반환은 CommonResponse 안에 `{revision,source:"LLM",summary,requirements,questions}`다. 각 requirement는 `{kind,value,description,source,evidence,imageIndex,priority,support}`. priority는 REQUIRED/PREFERRED이고, 사진·추정에서는 PREFERRED만 허용한다. 사진 source는 실제 첨부 번호(1-based), 원문 source는 정확한 부분 인용을 요구한다. 상세 계약과 단계별 UI 설명은 인접 RoomFit-Web/docs/ai-design-consultation.md 참조.

기존 서버 환경변수 재사용:

- ROOMFIT_LLM_FEEDBACK_ENABLED=true
- ROOMFIT_LLM_FEEDBACK_API_KEY: 서버에만 설정
- ROOMFIT_LLM_FEEDBACK_BASE_URL: 기존 OpenAI API 주소
- ROOMFIT_LLM_FEEDBACK_MODEL: 기존 선택 모델(현재 운영 설정의 gpt-6-luna를 자동으로 변경하지 않음)
- ROOMFIT_LLM_FEEDBACK_TIMEOUT_MS: 선택. 상담 요청은 25~60초로 제한.

미설정 503 / 제공자·응답 검증 실패 502 / 호출 제한 429 / 잘못된 사진 400 / 업로드 초과 413. 입력 보존 및 requestId 표시. 프로세스당 동시 2개, 분당 20회. 자동 재시도나 규칙 기반 성공 fallback 없음. 기존 텍스트 피드백 API/배치 엔진은 바꾸지 않는다.

Room/Layout/AgentContext 저장소 의존성이 없고, 사용자 입력과 첨부를 영구 저장하지 않는다. Spring multipart의 임시 파일은 요청 종료 시 관리된다. 이미지 메타데이터는 재인코딩으로 제거한다. 로그에는 내용·사진·키·제공자 오류 원문을 출력하지 않는다.

이 API는 해석 결과를 반환할 뿐, 사용자의 확인 또는 추천/가구 변경을 서버에서 실행하지 않는다. SUPPORTED는 기존 엔진에 대응하는 입력이 있다는 의미이며, 모든 요구의 실행 가능성/통로 검증을 뜻하지 않는다. 복수 목적 등은 NEEDS_CLARIFICATION, 엔진 미지원 세부 조건은 NEEDS_ENGINE_SUPPORT로 유지한다.

실제 OpenAI 호출은 배포 환경의 키로 별도 검증해야 한다. 현재 검증은 모의 제공자 응답을 사용한 계약/입력 검증이다.

공식 규격: https://developers.openai.com/api/docs/guides/images-vision · https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat


## 3단계 조건 준비 API (2026-10-18 구현)

`POST /api/design-consultation/prepare` JSON: revision, conditions[{id,description,priority}], furniture[{id,name,type}]. 이미지 재전송과 DB 쓰기 없이 확인된 문장을 기존 OpenAI 클라이언트로 정리한다. 각 조건마다 typed effects와 unresolved 설명을 반환한다. 총 조건 12개/문장 300자/가구 24개, 응답은 모든 조건 ID를 정확히 한 번씩 포함한다.

효과: PURPOSE/STYLE/PALETTE, REQUIRE_TYPE(카탈로그 종류·총 개수 1–24), EXCLUDE_TYPE(전부 제외), KEEP_ITEM(현재 ID). 나머지 효과의 count는 0이다. 활동 공간 수치·재질·이동 등 미지원 상세 조건은 unresolved에 남긴다. 기존 분석 API의 키·모델·25–60초 타임아웃·호출 제한을 공유하고, 제공자/검증 실패는 상세 본문을 노출하지 않는 502다.

필수 여부·충돌·공간 배치 가능성은 브라우저 컴파일러와 실제 배치 엔진이 결정한다. 준비 API의 응답만으로 추천 성공/배치 가능을 주장하지 않는다. 구현 시점 검증은 모의 제공자 기반이며 실제 OpenAI 호출·배포 결과는 별도 QA 기록으로 구분한다.

## 추천 준비를 포함한 1회 해석 (2026-10-18, 로컬 검증)

brief의 선택 필드 `recommendationReady:true`는 `{summary,requirements,questions}`와 조건별 effects/unresolved를 한 번의 제공자 호출로 해석한다. 응답의 `preparation:{revision,conditions:[{id,effects,unresolved}]}` ID는 최종 requirements 배열의 0-based 인덱스다. 각 요구의 kind/value는 sole effect에서 도출한다. 기본 false 호출은 기존 스키마와 해석 경로를 유지하고 preparation은 null이다.

별개 의미를 독립 조건으로 분리하도록 명세했다. 완전히 지원되는 동일 effects의 중복을 합치고, 직접 말한 태그·수량·제외와 충돌하는 추정 기본값은 제거한다. 미지원 상세 조건은 사라지지 않는다. 기존 인용·사진 번호·우선순위·실제 가구 ID와 prepare 효과/수량 검증을 모두 통과한 응답만 반환한다. 비문맥 사진에서 KEEP_ITEM을 추정하지 못한다.

원룸 제안값은 많은/풍성한 식물3개, 여러/몇 개2개, 조금/약간1개(추가 수가 아닌 총 수)다. 따뜻한 분위기는 내추럴, 따뜻한 색감은 베이지를 제안하며 명시 요청을 우선한다. 추정은 PREFERRED/INFERENCE와 제안 설명으로 표시한다. 별도로 요청하지 않은 재질·조명 변경 요구를 만들어 거절하지 않는다. 임의 좌표·실제 온도/조명 변경·예산·활동 공간 수치 등 미지원 상세를 성공으로 바꾸지는 않는다.

내용 수정이 없으면 프런트가 해석을 재사용하고, 수정된 선택 조건만 prepare를 호출한다. 모델·키·호출 제한·타임아웃은 기존 설정을 유지한다. 로컬 계약/서비스 테스트 26개와 bootJar 통과. 새 프롬프트의 운영 실호출은 아직 검증하지 않았다.

## 형용사 해석 범위 확장 (2026-10-18, 로컬 검증)

recommendation-ready 분석과 수정 조건 prepare가 공유하는 지침을 확장했다. 종류를 명시한 모든 지원 가구에 원룸 수량 제안을 적용한다. 많은/풍성한/넉넉한=화분3·다른 종류2, 여러/몇 개=2, 적은/조금/최소한=1. 숫자·제외는 제안보다 우선한다. 소파2개 등도 INFERENCE/PREFERRED와 ‘원룸 기준 제안’으로 표시하도록 명세했다. 실제 공간에 들어간다는 보장은 하지 않으며 기존 배치 검증을 통과해야 한다.

소지품이 많다는 요청은 수납 목적/용량 선호와 가구 개수를 구분한다. 종류 없는 가구 감소를 미니멀 스타일로 제안할 때에도 실제 총 가구 수 감소를 약속하지 않는다. 대상 없는 형용사·서로 충돌하는 명시 요구 등은 확인 질문을 유지한다. 생성 문장의 문체는 ‘입니다/습니다’로 통일하되 사용자 원문 근거는 그대로 인용한다.

새 API·추가 호출·정규식 자연어 파서를 만들지 않고 기존 한 번의 LLM 해석과 규칙 기반 실행 검증을 유지했다. 상담26테스트·bootJar 통과. 새 지침의 실제 OpenAI 의미 해석은 배포 후 검증해야 한다.
