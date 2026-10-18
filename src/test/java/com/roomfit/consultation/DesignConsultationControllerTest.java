package com.roomfit.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.common.GlobalExceptionHandler;
import com.roomfit.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DesignConsultationControllerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final OpenAiCompatibleLlmClient client = mock(OpenAiCompatibleLlmClient.class);
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DesignConsultationController(new DesignConsultationService(client, mapper)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    MockMultipartFile brief(String description) throws Exception {
        return new MockMultipartFile("brief", "brief.json", "application/json", mapper.writeValueAsBytes(Map.of(
                "revision", "revision-1", "description", description, "furniture", List.of())));
    }
    @Test void acceptsJsonPartAndImageFilesThroughTheRealService() throws Exception {
        when(client.completeStructuredContent(anyString(), any(), anyMap(), anyString())).thenReturn("""
            {"summary":"사진에서 취향을 읽었어요","requirements":[{"kind":"STYLE","value":"natural","description":"내추럴 스타일",
            "source":"REFERENCE_IMAGE","priority":"PREFERRED","evidence":"목재가 보여요","imageIndex":1}],"questions":[]}
            """);
        mvc.perform(multipart("/api/design-consultation/analyze").file(brief("")).file(DesignConsultationServiceTest.photo("reference.png")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("LLM"))
                .andExpect(jsonPath("$.data.requirements[0].imageIndex").value(1));
    }
    @Test void validatesDescriptionBeforeProvider() throws Exception {
        mvc.perform(multipart("/api/design-consultation/analyze").file(brief("x".repeat(2001))))
                .andExpect(status().isBadRequest()); verifyNoInteractions(client);
    }
    @Test void returnsQuestionOnlyForInsufficientInputWithoutInventingConditions() throws Exception {
        when(client.completeStructuredContent(anyString(), any(), anyMap(), anyString())).thenReturn("""
            {"summary":"어떤 방을 원하시는지 알려주세요","requirements":[],"questions":["좋아하는 분위기가 있나요?"]}
            """);
        mvc.perform(multipart("/api/design-consultation/analyze").file(brief("안녕")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requirements").isEmpty())
                .andExpect(jsonPath("$.data.questions[0]").isNotEmpty());
    }
    @Test void missingMetadataPartIsA400RatherThan500() throws Exception {
        mvc.perform(multipart("/api/design-consultation/analyze").file(DesignConsultationServiceTest.photo("reference.png")))
                .andExpect(status().isBadRequest()); verifyNoInteractions(client);
    }

    @Test void preparesConfirmedConditionsThroughTheRealService() throws Exception {
        when(client.completeStructuredContent(anyString(), any(), anyMap(), eq("roomfit_consultation_preparation"))).thenReturn("""
            {"conditions":[{"id":"0","effects":[{"kind":"REQUIRE_TYPE","value":"desk","count":2}],"unresolved":[]}]}
            """);
        mvc.perform(post("/api/design-consultation/prepare").contentType("application/json").content("""
            {"revision":"r","conditions":[{"id":"0","description":"책상 두 개 필요","priority":"REQUIRED"}],"furniture":[]}
            """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value("r"))
            .andExpect(jsonPath("$.data.conditions[0].effects[0].count").value(2));
    }
    @Test void rejectsInvalidOrDuplicateConfirmedConditionsBeforeProvider() throws Exception {
        for(String conditions:List.of("[]", "[{\"id\":\"0\",\"description\":\"\",\"priority\":\"REQUIRED\"}]",
                "[{\"id\":\"0\",\"description\":\"책상\",\"priority\":\"FAKE\"}]",
                "[{\"id\":\"0\",\"description\":\"책상\",\"priority\":\"REQUIRED\"},{\"id\":\"0\",\"description\":\"책장\",\"priority\":\"REQUIRED\"}]")) {
            mvc.perform(post("/api/design-consultation/prepare").contentType("application/json")
                    .content("{\"revision\":\"r\",\"conditions\":"+conditions+",\"furniture\":[]}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(client);
    }
}
