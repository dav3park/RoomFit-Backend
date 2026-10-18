# 서버 테스트 실패 11개 정리 — 2026-10-18

기준 커밋: `5d3f96f`. 이 작업에서는 커밋·push·배포를 하지 않았습니다.

## 원인과 수정

| 실패 항목 | 개수 | 분류 | 처리 |
|---|---:|---|---|
| `LayoutDraftControllerTest.reeditDraft_preservesManagedFurnitureAcrossAddFeedbackConfirmAndRoomRead`, `LayoutFeedbackControllerTest.feedback_addFurnitureKeepsExistingResponseShapeAndUsesCatalogMetadata`, `DeterministicFurnitureCompositionExecutorTest.productionCatalogAddsRepresentativeCanonicalVariants` | 3 | 상품 선택 오류 | 생성형 가구 추가 시 조건에 맞는 JSON 상품이 있으면 구형 기본 도형 대신 해당 상품을 사용합니다. 맞는 JSON 상품이 없으면 구형 상품을 계속 허용합니다. |
| `DeterministicFurnitureCompositionExecutorTest.addValidationBudgetNeverExceedsTwentyFourCandidates` | 1 | 중복 검증 | 이미 구한 `validateChange` 결과로 안전성·점수를 판정합니다. 동일 후보를 다시 검증하던 호출과 사용되지 않는 헬퍼를 제거했습니다. |
| `FurnitureDomainPolicyControllerTest.furnitureAdditionRejectsExistingAndNewConflictInEitherDirection` | 3 | 오래된 모의 응답 | 개별 추가 처리에서 읽는 `operationResults`를 포함하도록 응답을 보완했습니다. 로프트 침대·책상 충돌 시 422 및 저장 상태 불변 단언을 유지합니다. |
| `RecommendationFeasibilityTest.eachCanonicalFurnitureType_isRecognizedAndPlacedInLargeRoom`, `RecommendationCatalogLifecycleControllerTest.eachCanonicalType_survivesRecommendationLayoutAndRoomLifecycle`의 monitor/TV 사례 | 4 | 잘못된 테스트 전제 | 책상·TV장을 제공하고 화면 가구가 받침 위치에 배치되는지 확인합니다. 실제 배치 정책은 변경하지 않습니다. 저장·확정·재조회 검증도 유지합니다. |

## 회귀 검증

- 기존 실패 11개: 관련 6개 테스트 클래스, 102개 테스트에서 동일하게 재현했습니다.
- 조명 JSON 상품 우선 선택 테스트: 수정 전 실패를 확인했습니다.
- 상품 교체의 모호성 검사는 전체 후보를 유지합니다. 우선 선택 필터가 이 경로에 적용되지 않는 별도 회귀 테스트를 추가하고 실패→통과를 확인했습니다.
- 구형 상품만 있는 카탈로그의 대체 동작과 받침 없는 모니터·TV의 미배치 동작도 확인합니다.
- 최종 전체 결과: **848개 실행, 실패 0, 오류 0, 건너뜀 0**.
- `test`, `bootJar`, `git diff --check` 통과.
- 별도 읽기 전용 코드 리뷰에서 중요한 결함이 보고되지 않았습니다.

명령:

```sh
./gradlew --init-script /tmp/roomfit-deploy-java25.gradle test bootJar
```

로컬 JDK 21이 없어 해당 실행에만 JDK 25를 적용했습니다. 프로젝트·운영 Java 설정은 변경하지 않았습니다. 최종 실행 로그는 `/tmp/roomfit-debt-final.txt`, 상세 결과는 `build/test-results/test/` 및 `build/reports/tests/test/`에 있습니다.

## 범위와 한계

- 프론트엔드, 커튼·파티션 역할 규칙, 신규 가구 100개는 변경하지 않았습니다.
- 운영 서비스 호출·실제 OpenAI 호출·운영 데이터 저장·브라우저 UI 검증은 이 작업에서 수행하지 않았습니다. API 저장·확정은 로컬 H2 통합 테스트로 확인했습니다.
- 중복 후보 검증 제거는 서버 가구 추가 경로의 개선이며 OpenAI 응답시간 단축을 입증한 결과가 아닙니다.
- 과거 AI 응답 실패의 정확한 위반 필드가 미확정이라는 기존 한계는 그대로입니다. 이 테스트 정리가 해당 과거 원인을 새로 밝혀낸 것은 아닙니다.
