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
