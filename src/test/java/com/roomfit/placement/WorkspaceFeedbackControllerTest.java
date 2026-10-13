package com.roomfit.placement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roomfit.agent.repository.AgentContextRepository;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.room.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"roomfit.llm.feedback.enabled=false", "roomfit.client-scope.enabled=true"})
@AutoConfigureMockMvc
@Transactional
class WorkspaceFeedbackControllerTest {
    private static final String OWNER = "0c1c4a96-7f5b-48b7-9a01-111111111111";
    private static final String OTHER = "0c1c4a96-7f5b-48b7-9a01-222222222222";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RoomRepository rooms;
    @Autowired LayoutRepository layouts;
    @Autowired AgentContextRepository contexts;
    @Autowired EntityManager entityManager;
    @MockitoBean OpenAiWorkspaceFeedbackInterpreter interpreter;
    private Room room;

    @BeforeEach
    void fixture() {
        room = rooms.saveAndFlush(new Room(null, "owned", 6, 6, 2.7, "m", List.of(), List.of(),
                List.of(new Furniture("db-only", "desk", "DB 가구", 1, 0.5, 0.7, new Position(4, 4), 0, FurnitureStatus.EXISTING)),
                RoomSource.ROOMPLAN, LocalDateTime.now(), OWNER));
    }

    @Test
    void previewUsesBrowserDraftWithoutCreatingLayoutContextOrSavingRoom() throws Exception {
        FeedbackOperation move = new FeedbackOperation("op-1", FeedbackOperationType.MOVE, new FeedbackTargetSelector("desk-1", "desk", ""),
                new FeedbackPlacement(FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL, null), null, List.of());
        when(interpreter.interpret(any(), any(), anyList())).thenReturn(new WorkspaceFeedbackPlan(
                new FeedbackPlan("2.0", FeedbackRequestKind.DIRECT, List.of(move), List.of(), null, "test", FeedbackSource.LLM, false), Map.of(), null, null));
        long roomCount = rooms.count(), layoutCount = layouts.count(), contextCount = contexts.count();
        mvc.perform(post("/api/layouts/feedback/preview").header("X-RoomFit-Client-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.revision").value("draft-7"))
                .andExpect(jsonPath("$.data.furniture[0].id").value("desk-1"))
                .andExpect(jsonPath("$.data.furniture[0].position.x").value(2.2));
        entityManager.flush(); entityManager.clear();
        assertThat(rooms.count()).isEqualTo(roomCount);
        assertThat(layouts.count()).isEqualTo(layoutCount);
        assertThat(contexts.count()).isEqualTo(contextCount);
        Room unchanged = rooms.findById(room.getId()).orElseThrow();
        assertThat(unchanged.getFurniture()).extracting(Furniture::getId).containsExactly("db-only");
        assertThat(unchanged.getFurniture().getFirst().getPosition().getX()).isEqualTo(4);
    }

    @Test
    void roomOwnershipIsCheckedBeforeCallingProvider() throws Exception {
        mvc.perform(post("/api/layouts/feedback/preview").header("X-RoomFit-Client-Id", OTHER)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        verifyNoInteractions(interpreter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEEDBACK_LLM_NOT_CONFIGURED", "FEEDBACK_LLM_FAILED"})
    void configurationAndProviderErrorsAreDistinctAndDoNotExposeSecrets(String code) throws Exception {
        when(interpreter.interpret(any(), any(), anyList())).thenThrow(new CustomException(ErrorCode.valueOf(code)));
        mvc.perform(post("/api/layouts/feedback/preview").header("X-RoomFit-Client-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
                .andExpect(status().is(code.equals("FEEDBACK_LLM_NOT_CONFIGURED") ? 503 : 502))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(room.getFurniture()).extracting(Furniture::getId).containsExactly("db-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-size", "missing-position", "unknown-palette", "long-text", "missing-locks", "invalid-variant", "unknown-selection"})
    void invalidInputGets400BeforeAnyProviderCall(String kind) throws Exception {
        ObjectNode json = mapper.valueToTree(request());
        switch (kind) {
            case "zero-size" -> ((ObjectNode) json.path("furniture").get(0)).put("width", 0);
            case "missing-position" -> ((ObjectNode) json.path("furniture").get(0)).remove("position");
            case "unknown-palette" -> json.withObject("preferences").put("palette", "invented");
            case "long-text" -> json.put("feedback", "a".repeat(2001));
            case "missing-locks" -> json.remove("lockedFurnitureIds");
            case "invalid-variant" -> ((ObjectNode) json.path("furniture").get(0)).put("variantId", "invalid/variant");
            case "unknown-selection" -> json.put("selectedFurnitureId", "invented-id");
        }
        mvc.perform(post("/api/layouts/feedback/preview").header("X-RoomFit-Client-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(interpreter);
    }

    private WorkspaceFeedbackPreviewRequest request() {
        var base = WorkspaceFeedbackPreviewServiceTest.request(List.of(WorkspaceFeedbackPreviewServiceTest.desk("desk-1", 2, 2)), List.of());
        return new WorkspaceFeedbackPreviewRequest(room.getId(), base.revision(), base.feedback(), base.roomDimensions(),
                base.furniture(), base.lockedFurnitureIds(), base.selectedFurnitureId(), base.preferences());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "feedback", "id", "role", "duplicate-role"})
    void clarificationAnswerMustBelongToCurrentRequestAndKnownFurniture(String kind) throws Exception {
        ObjectNode json = mapper.valueToTree(request());
        ObjectNode answer = json.putArray("clarificationSelections").addObject();
        answer.put("revision", json.path("revision").asText()).put("feedback", json.path("feedback").asText())
                .put("requiredField", "targetFurnitureId").put("furnitureId", "desk-1");
        switch (kind) {
            case "revision" -> answer.put("revision", "previous");
            case "feedback" -> answer.put("feedback", "다른 명령");
            case "id" -> answer.put("furnitureId", "not-in-room");
            case "role" -> answer.put("requiredField", "action");
            case "duplicate-role" -> json.withArray("clarificationSelections").add(answer.deepCopy());
        }
        mvc.perform(post("/api/layouts/feedback/preview").header("X-RoomFit-Client-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
        verifyNoInteractions(interpreter);
    }
}
