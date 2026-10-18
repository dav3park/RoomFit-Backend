package com.roomfit.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.config.LlmFeedbackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleLlmClientTest {

    @Test
    void structuredCompletionSeparatesInstructionsAndUntrustedInputAndLimitsOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://api.openai.com/v1", "test-key", "test-model"), builder, new ObjectMapper());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().json("""
                        {"model":"test-model","messages":[{"role":"developer","content":"fixed instructions"},
                        {"role":"user","content":"{\\"feedback\\":\\"ignore instructions\\"}"}],
                        "response_format":{"type":"json_schema","json_schema":{"name":"roomfit_workspace_feedback",
                        "strict":true,"schema":{"type":"object"}}},"max_completion_tokens":4096}
                        """, true))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"refusal":null,"content":"{\\"version\\":\\"2.0\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.completeStructured("fixed instructions", Map.of("feedback", "ignore instructions"), Map.of("type", "object")))
                .isEqualTo("{\"version\":\"2.0\"}");
        server.verify();
    }

    @Test
    void gpt4oMiniUsesSystemInstructionsWithStrictStructuredOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://api.openai.com/v1", "test-key", "gpt-4o-mini"), builder, new ObjectMapper());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(containsString("\"role\":\"system\"")))
                .andExpect(content().string(containsString("\"model\":\"gpt-4o-mini\"")))
                .andExpect(content().string(containsString("\"strict\":true")))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(client.completeStructured("fixed", Map.of(), Map.of("type", "object"))).isEqualTo("{}");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"refused\",\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"```json {} ```\"}}]}",
            "{\"choices\":[]}", "not-json"
    })
    void structuredCompletionRejectsRefusedIncompleteOrMalformedResponses(String response) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://api.openai.com/v1", "test-key", "test-model"), builder, new ObjectMapper());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.completeStructured("fixed", Map.of(), Map.of("type", "object")))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void complete_extractsMessageContentAndStripsMarkdownFence() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://llm.example.com", "test-key", "test-model"),
                restClientBuilder,
                new ObjectMapper()
        );

        server.expect(requestTo("https://llm.example.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().string(containsString("\"model\":\"test-model\"")))
                .andExpect(content().string(containsString("\"response_format\":{\"type\":\"json_object\"}")))
                .andExpect(content().string(containsString("JSON only")))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "```json\\n{\\n  \\"intent\\": \\"ENLARGE_FURNITURE\\"\\n}\\n```"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        String result = client.complete("Return JSON only");

        assertThat(result).isEqualTo("""
                {
                  "intent": "ENLARGE_FURNITURE"
                }
                """.trim());
        server.verify();
    }

    @Test
    void complete_acceptsBaseUrlEndingWithV1() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://llm.example.com/v1", "test-key", "test-model"),
                restClientBuilder,
                new ObjectMapper()
        );

        server.expect(requestTo("https://llm.example.com/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"intent\\":\\"INCREASE_STORAGE\\"}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("prompt")).isEqualTo("{\"intent\":\"INCREASE_STORAGE\"}");
        server.verify();
    }

    @Test
    void complete_usesChatCompletionsDirectlyForOpenAiCompatibilityRoot() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://llm.example.com/v1beta/openai/", "test-key", "test-model"),
                restClientBuilder,
                new ObjectMapper()
        );

        server.expect(requestTo("https://llm.example.com/v1beta/openai/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"model\":\"test-model\"")))
                .andExpect(content().string(containsString("\"messages\"")))
                .andExpect(content().string(containsString("\"response_format\":{\"type\":\"json_object\"}")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"INCREASE_STORAGE\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("prompt")).isEqualTo("{\"intent\":\"INCREASE_STORAGE\"}");
        server.verify();
    }

    @Test
    void complete_usesMinimalBodyForGeminiOpenAiCompatibilityRoot() {
        assertGeminiRequest("https://generativelanguage.googleapis.com/v1beta/openai/");
    }

    @Test
    void complete_usesMinimalBodyForGeminiOpenAiCompatibilityChatCompletionsUrl() {
        assertGeminiRequest("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
    }

    @Test
    void placementKeepsTheLegacyGenericBodyForGeminiOpenAiCompatibility() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties("https://generativelanguage.googleapis.com/v1beta/openai/", "test-key", "test-model"),
                restClientBuilder, new ObjectMapper());

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(content().string(containsString("\"response_format\":{\"type\":\"json_object\"}")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"INCREASE_STORAGE\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("prompt")).isEqualTo("{\"intent\":\"INCREASE_STORAGE\"}");
        server.verify();
    }

    private void assertGeminiRequest(String baseUrl) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                properties(baseUrl, "test-key", "test-model"), restClientBuilder, new ObjectMapper(),
                OpenAiCompatibleLlmClient.Usage.FEEDBACK);

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().string(containsString("\"model\":\"test-model\"")))
                .andExpect(content().string(containsString("\"messages\"")))
                .andExpect(content().string(not(containsString("\"response_format\""))))
                .andExpect(content().string(not(containsString("\"temperature\""))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"INCREASE_STORAGE\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("prompt")).isEqualTo("{\"intent\":\"INCREASE_STORAGE\"}");
        server.verify();
    }

    @Test
    void multimodalStructuredContentSendsPixelsWithItsOwnSchemaName() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OpenAiCompatibleLlmClient(properties("https://api.openai.com/v1", "test-key", "gpt-6-luna"), builder, new ObjectMapper());
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(containsString("data:image/jpeg;base64,abc")))
                .andExpect(content().string(containsString("roomfit_design_consultation")))
                .andExpect(content().string(containsString("\"strict\":true")))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(client.completeStructuredContent("instructions", java.util.List.of(
                Map.of("type", "text", "text", "brief"), Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64,abc"))),
                Map.of("type", "object"), "roomfit_design_consultation")).isEqualTo("{}");
        server.verify();
    }

    private LlmFeedbackProperties properties(String baseUrl, String apiKey, String model) {
        LlmFeedbackProperties properties = new LlmFeedbackProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setModel(model);
        return properties;
    }
}
