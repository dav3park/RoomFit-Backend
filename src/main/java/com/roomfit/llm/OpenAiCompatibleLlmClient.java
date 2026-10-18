package com.roomfit.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.config.LlmFeedbackProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final boolean geminiOpenAiCompatibility;

    public OpenAiCompatibleLlmClient(LlmFeedbackProperties properties, ObjectMapper objectMapper) {
        this(properties, restClientBuilder(properties), objectMapper, Usage.PLACEMENT);
    }

    public static OpenAiCompatibleLlmClient forFeedback(LlmFeedbackProperties properties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleLlmClient(properties, restClientBuilder(properties), objectMapper, Usage.FEEDBACK);
    }

    OpenAiCompatibleLlmClient(LlmFeedbackProperties properties,
                              RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper) {
        this(properties, restClientBuilder, objectMapper, Usage.PLACEMENT);
    }

    OpenAiCompatibleLlmClient(LlmFeedbackProperties properties,
                              RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper,
                              Usage usage) {
        this.restClient = restClientBuilder
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.endpoint = normalizeEndpoint(properties.getBaseUrl());
        this.model = properties.getModel();
        this.geminiOpenAiCompatibility = usage == Usage.FEEDBACK
                && isGeminiOpenAiCompatibilityEndpoint(properties.getBaseUrl());
    }

    @Override
    public String complete(String prompt) {
        Map<String, Object> requestBody = requestBody(prompt);

        String responseBody = restClient.post()
                .uri(endpoint)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return stripMarkdownCodeFence(extractText(responseBody));
    }

    /** The workspace preview has a separate schema; legacy feedback/placement requests stay unchanged. */
    public String completeStructured(String instructions, Object input, Map<String, Object> schema) {
        try {
            return completeStructuredContent(instructions, objectMapper.writeValueAsString(input), schema, "roomfit_workspace_feedback");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid workspace feedback request");
        }
    }

    /** Multimodal content stays separate from the legacy text-only interpreter. */
    public String completeStructuredContent(String instructions, Object content, Map<String, Object> schema, String name) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", model.startsWith("gpt-4o") ? "system" : "developer", "content", instructions),
                            Map.of("role", "user", "content", content)),
                    "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                            "name", name, "strict", true, "schema", schema)),
                    "max_completion_tokens", 4096);
            String response = restClient.post().uri(endpoint).body(body).retrieve().body(String.class);
            JsonNode choice = objectMapper.readTree(response).path("choices").path(0);
            JsonNode message = choice.path("message");
            if (!"stop".equals(choice.path("finish_reason").asText())
                    || (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull())
                    || !message.path("content").isTextual()) {
                throw new IllegalStateException("Incomplete or refused workspace feedback");
            }
            String answer = message.path("content").asText();
            if (answer.length() > 32_000 || !objectMapper.readTree(answer).isObject()) {
                throw new IllegalStateException("Invalid workspace feedback response");
            }
            return answer;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid workspace feedback response");
        }
    }

    private String extractText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("intent")) {
                return responseBody;
            }

            JsonNode firstChoice = root.path("choices").path(0);
            String messageContent = firstChoice.path("message").path("content").asText("");
            if (!messageContent.isBlank()) {
                return messageContent;
            }
            return firstChoice.path("text").asText("");
        } catch (JsonProcessingException e) {
            return responseBody;
        }
    }

    private String stripMarkdownCodeFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        String withoutOpeningFence = trimmed.replaceFirst("^```(?:json)?\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }

    private static RestClient.Builder restClientBuilder(LlmFeedbackProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(1, properties.getTimeoutMs());
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private String normalizeEndpoint(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        // Gemini's documented OpenAI-compatible base URL ends in /openai and
        // expects /chat/completions directly, rather than OpenAI's /v1 prefix.
        if (trimmed.endsWith("/openai")) {
            return trimmed + "/chat/completions";
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/chat/completions";
        }
        return trimmed + "/v1/chat/completions";
    }

    private Map<String, Object> requestBody(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (!geminiOpenAiCompatibility) {
            requestBody.put("temperature", 0);
            requestBody.put("response_format", Map.of("type", "json_object"));
        }
        return Map.copyOf(requestBody);
    }

    private boolean isGeminiOpenAiCompatibilityEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            URI uri = new URI(baseUrl.trim());
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
            return "generativelanguage.googleapis.com".equalsIgnoreCase(uri.getHost())
                    && path.contains("/openai");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    enum Usage {
        FEEDBACK,
        PLACEMENT
    }
}
