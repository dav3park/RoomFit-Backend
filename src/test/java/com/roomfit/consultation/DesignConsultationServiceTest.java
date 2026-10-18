package com.roomfit.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.config.LlmFeedbackProperties;
import com.roomfit.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesignConsultationServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final OpenAiCompatibleLlmClient client = mock(OpenAiCompatibleLlmClient.class);
    final DesignConsultationService service = new DesignConsultationService(client, mapper);
    final DesignConsultationRequest request = new DesignConsultationRequest("revision-1", "재택근무를 하고 요가할 공간이 필요해요. 침대는 유지할래요.",
            List.of(new DesignConsultationRequest.Item("bed-1", "내 침대", "bed")));

    Map<String, Object> item(String kind, String value, String description, String source, String evidence, Integer index) {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", kind); result.put("value", value); result.put("description", description); result.put("source", source);
        result.put("priority", "PREFERRED"); result.put("evidence", evidence); result.put("imageIndex", index); return result;
    }
    String response(List<Map<String, Object>> items) throws Exception {
        return mapper.writeValueAsString(Map.of("summary", "생활 조건을 정리했어요.", "requirements", items, "questions", List.of()));
    }
    Map<String, Object> work() { return item("PURPOSE", "업무 / 공부", "재택근무를 할 업무 공간", "USER_TEXT", "재택근무", null); }
    void provider(String content) {
        when(client.completeStructuredContent(anyString(), any(), anyMap(), eq("roomfit_design_consultation"))).thenReturn(content);
    }
    static MockMultipartFile photo(String name) throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("images", name, "image/png", bytes.toByteArray());
    }
    @Test void analyzesTextWithoutCreatingAnyLayout() throws Exception {
        provider(response(List.of(work(), item("OTHER", "", "요가할 공간이 필요해요", "USER_TEXT", "요가할 공간", null))));
        var result = service.analyze(request, List.of());
        assertThat(result.revision()).isEqualTo("revision-1"); assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.requirements()).extracting(DesignConsultationResult.Requirement::support).containsExactly("SUPPORTED", "NEEDS_ENGINE_SUPPORT");
    }
    @Test void sendsActualImageContentAndChecksImageProvenance() throws Exception {
        provider(response(List.of(item("STYLE", "natural", "밝은 목재", "REFERENCE_IMAGE", "사진의 밝은 목재", 1))));
        assertThat(service.analyze(request, List.of(photo("not-a-prompt.png"))).requirements().getFirst().imageIndex()).isEqualTo(1);
        var content = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(client).completeStructuredContent(anyString(), content.capture(), anyMap(), anyString());
        var blocks = (List<?>) content.getValue(); assertThat(blocks).hasSize(2);
        assertThat(mapper.writeValueAsString(blocks.get(1))).contains("data:image/jpeg;base64,").contains("image_url").doesNotContain("not-a-prompt");
    }
    @Test void rejectsFabricatedQuotesIdsAndImages() throws Exception {
        for (var item : List.of(item("STYLE", "natural", "내추럴", "USER_TEXT", "없는 문장", null),
                item("KEEP_FURNITURE", "invented", "침대 유지", "USER_TEXT", "침대는 유지", null),
                item("STYLE", "natural", "나무", "REFERENCE_IMAGE", "나무가 보여요", 1))) {
            assertThatThrownBy(() -> service.parse(response(List.of(item)), request, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void preservesMultiplePurposesInsteadOfSilentlyChoosingOne() throws Exception {
        var result = service.parse(response(List.of(work(), item("PURPOSE", "휴식", "휴식도 중요", "INFERENCE", "원룸 생활의 추정", null))), request, 0);
        assertThat(result.requirements()).hasSize(2).allMatch(item -> item.support().equals("NEEDS_CLARIFICATION"));
    }
    @Test void doesNotPermitImageToIdentifyAnExistingFurnitureToKeep() throws Exception {
        assertThatThrownBy(() -> service.parse(response(List.of(item("KEEP_FURNITURE", "bed-1", "침대 유지", "REFERENCE_IMAGE", "침대가 보임", 1))), request, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsFalseMimeCorruptAndOverlargeImagesBeforeProvider() throws Exception {
        for (var image : List.of(new MockMultipartFile("images", "fake.png", "image/png", "text".getBytes()),
                new MockMultipartFile("images", "image.png", "image/jpeg", photo("x").getBytes()),
                new MockMultipartFile("images", "image.gif", "image/gif", photo("x").getBytes()),
                new MockMultipartFile("images", "big.png", "image/png", new byte[5 * 1024 * 1024 + 1]))) {
            assertThatThrownBy(() -> service.analyze(request, List.of(image))).isInstanceOfSatisfying(CustomException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONSULTATION_IMAGE));
        }
        verifyNoInteractions(client);
    }
    @Test void imagePreferencesCannotBecomeMandatoryWithoutUserConfirmation() throws Exception {
        var image = item("STYLE", "natural", "내추럴", "REFERENCE_IMAGE", "나무가 보임", 1);
        image.put("priority", "REQUIRED");
        assertThatThrownBy(() -> service.parse(response(List.of(image)), request, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsEmptyInputTooManyPhotosAndDuplicateFurnitureIds() throws Exception {
        var empty = new DesignConsultationRequest("r", "   ", List.of());
        assertThatThrownBy(() -> service.analyze(empty, List.of())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.analyze(request, List.of(photo("1"), photo("2"), photo("3"), photo("4")))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.analyze(new DesignConsultationRequest("r", "hello", List.of(request.furniture().getFirst(), request.furniture().getFirst())), List.of()))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(client);
    }
    @Test void mapsProviderErrorsToSafeFailureWithoutFallbackSuccess() {
        when(client.completeStructuredContent(anyString(), any(), anyMap(), anyString())).thenThrow(new RuntimeException("private-text-and-key"));
        assertThatThrownBy(() -> service.analyze(request, List.of())).isInstanceOfSatisfying(CustomException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONSULTATION_FAILED); assertThat(error.getMessage()).doesNotContain("private");
        });
    }
    @Test void disabledConfigurationDoesNotPretendToAnalyze() {
        var disabled = new DesignConsultationService(new LlmFeedbackProperties(), mapper);
        assertThatThrownBy(() -> disabled.analyze(request, List.of())).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONSULTATION_NOT_CONFIGURED));
    }
    @Test void identifiesInvalidEvidenceWithoutLoggingItsContent() throws Exception {
        var invalid = item("STYLE", "natural", "내추럴", "USER_TEXT", "private fabricated quote", null);
        assertThatThrownBy(() -> service.parse(response(List.of(invalid)), request, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("EVIDENCE_NOT_IN_INPUT");
    }
    @Test void separatesInvalidModelResponseFromProviderConnectionFailure() throws Exception {
        provider(response(List.of(item("STYLE", "natural", "내추럴", "USER_TEXT", "fabricated", null))));
        assertThatThrownBy(() -> service.analyze(request, List.of())).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode().name()).isEqualTo("CONSULTATION_RESPONSE_INVALID"));
    }
    @Test void limitsProviderCallsAcrossAllClientIds() throws Exception {
        provider(response(List.of(work())));
        for (int i = 0; i < 20; i++) service.analyze(request, List.of());
        assertThatThrownBy(() -> service.analyze(request, List.of())).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONSULTATION_RATE_LIMITED));
        verify(client, times(20)).completeStructuredContent(anyString(), any(), anyMap(), anyString());
    }
}
