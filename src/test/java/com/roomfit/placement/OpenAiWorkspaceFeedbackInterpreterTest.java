package com.roomfit.placement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roomfit.config.LlmFeedbackProperties;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.room.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OpenAiWorkspaceFeedbackInterpreterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiWorkspaceFeedbackInterpreter interpreter = new OpenAiWorkspaceFeedbackInterpreter(new LlmFeedbackProperties(), mapper);
    private final List<Furniture> furniture = List.of(new Furniture("desk-1", "desk", "책상", 1.2, 0.6, 0.72,
            new Position(2, 2), 0, FurnitureStatus.EXISTING));

    @Test
    void movePlanUsesKnownIdsAndCarriesExplicitPreservation() throws Exception {
        ObjectNode root = move();
        root.withArray("preserve").addObject().put("furnitureId", "desk-1").putArray("fields").add("DIMENSIONS");
        WorkspaceFeedbackPlan parsed = interpreter.parse(root.toString(), "크기는 그대로 두고 방 기준 오른쪽으로 옮겨줘", furniture);
        assertThat(parsed.plan().source()).isEqualTo(FeedbackSource.LLM);
        assertThat(parsed.plan().operations().getFirst().target().furnitureId()).isEqualTo("desk-1");
        assertThat(parsed.preserve().get("desk-1")).containsExactly("DIMENSIONS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-id", "deleted-id"})
    void unknownTargetIdsAreRejected(String id) throws Exception {
        ObjectNode root = move();
        ((ObjectNode)root.path("operations").get(0).path("target")).put("furnitureId", id);
        assertThatThrownBy(() -> interpreter.parse(root.toString(), "책상 이동", furniture)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongTypeOrInventedCoordinatesOrMissingRequiredFieldAreRejected() throws Exception {
        ObjectNode root = move();
        ((ObjectNode)root.path("operations").get(0).path("target")).put("furnitureType", "bed");
        assertThatThrownBy(() -> interpreter.parse(root.toString(), "이동", furniture)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode coordinates = move();
        ((ObjectNode)coordinates.path("operations").get(0)).put("x", 2);
        assertThatThrownBy(() -> interpreter.parse(coordinates.toString(), "이동", furniture)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode missing = move();
        missing.remove("preserve");
        assertThatThrownBy(() -> interpreter.parse(missing.toString(), "이동", furniture)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedCommandReturnsNoOperationsAndKnownReason() throws Exception {
        ObjectNode root = question();
        root.put("unsupportedReason", "UNSUPPORTED_OPERATION");
        WorkspaceFeedbackPlan parsed = interpreter.parse(root.toString(), "가로를 1000mm로 보정해줘", furniture);
        assertThat(parsed.unsupportedReason()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(parsed.plan().operations()).isEmpty();
    }

    @Test
    void clarificationCandidatesMustBeKnownAndMatchRequestedType() throws Exception {
        ObjectNode root = question();
        root.withObject("clarification").withArray("candidateFurnitureIds").add("desk-1");
        assertThat(interpreter.parse(root.toString(), "책상 이동", furniture).clarification().candidates())
                .extracting(candidate -> candidate.furnitureId()).containsExactly("desk-1");
        root.withObject("clarification").withArray("candidateFurnitureIds").add("invented");
        assertThatThrownBy(() -> interpreter.parse(root.toString(), "책상 이동", furniture)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"책상을 배치해줘", "책상을 추가하지 말고 옮겨줘", "책상 추가 말고 이동해줘"})
    void additionRequiresExplicitNonNegatedCreation(String feedback) throws Exception {
        ObjectNode root = move();
        ObjectNode op = (ObjectNode)root.path("operations").get(0);
        op.put("type", "ADD_FURNITURE");
        op.withObject("target").put("furnitureId", "");
        op.withObject("placement").put("relation", "CENTER").putNull("magnitude");
        op.set("productRequirements", mapper.readTree("{\"furnitureType\":\"desk\",\"sizePreference\":\"ANY\",\"storagePreferred\":false,\"styleKeywords\":[]}"));
        assertThatThrownBy(() -> interpreter.parse(root.toString(), feedback, furniture)).isInstanceOf(IllegalArgumentException.class);
        assertThat(interpreter.parse(root.toString(), "책상 하나 추가해줘", furniture).plan().operations().getFirst().type())
                .isEqualTo(FeedbackOperationType.ADD_FURNITURE);
    }

    @Test
    void duplicatePreservationFieldsAndNonexistentPreservedIdsAreRejected() throws Exception {
        ObjectNode root = move();
        root.withArray("preserve").addObject().put("furnitureId", "desk-1").putArray("fields").add("POSITION").add("POSITION");
        assertThatThrownBy(() -> interpreter.parse(root.toString(), "유지", furniture)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode badId = move();
        badId.withArray("preserve").addObject().put("furnitureId", "invented").putArray("fields").add("POSITION");
        assertThatThrownBy(() -> interpreter.parse(badId.toString(), "유지", furniture)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledClientDoesNotInventAnAiSuccess() {
        assertThat(interpreter.isAvailable()).isFalse();
        assertThatThrownBy(() -> interpreter.interpret(null, null, furniture)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_LLM_NOT_CONFIGURED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.openai.com/v1", "https://llm.example.com/v1", "https://api.openai.com/v1?key=secret",
            "https://user:password@api.openai.com/v1", "https://api.openai.com:8080/v1", "https://api.openai.com/other"})
    void workspaceDoesNotSendCredentialsToOtherEndpoints(String url) {
        LlmFeedbackProperties properties = new LlmFeedbackProperties();
        properties.setEnabled(true); properties.setBaseUrl(url); properties.setApiKey("test-key"); properties.setModel("test-model");
        assertThat(new OpenAiWorkspaceFeedbackInterpreter(properties, mapper).isAvailable()).isFalse();
    }

    private ObjectNode move() throws Exception {
        return (ObjectNode)mapper.readTree("""
                {"version":"2.0","requestKind":"DIRECT","operations":[{"operationId":"op-1","type":"MOVE",
                "target":{"furnitureId":"desk-1","furnitureType":"desk","labelKeyword":"","locationHint":null,"ordinal":null},
                "referenceTarget":null,"placement":{"relation":"RIGHT","magnitude":"SMALL","orientation":null,"side":null},
                "constraints":null,"productRequirements":null,"replacementRequirements":null,"dependsOn":[]}],
                "goals":[],"clarification":null,"reason":"책상 이동","preserve":[],"unsupportedReason":null}
                """);
    }
    private ObjectNode question() throws Exception {
        ObjectNode root = move(); root.put("requestKind", "CLARIFICATION"); root.withArray("operations").removeAll();
        root.set("clarification", mapper.readTree("""
                {"question":"어느 책상을 변경할까요?","targetFurnitureType":"desk","requiredField":"targetFurnitureId","candidateFurnitureIds":[]}
                """));
        return root;
    }
}
