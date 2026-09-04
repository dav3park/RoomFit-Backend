package com.roomfit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.placement.FallbackFeedbackIntentParser;
import com.roomfit.placement.FallbackFeedbackPlanInterpreter;
import com.roomfit.placement.FallbackPlacementService;
import com.roomfit.placement.FeedbackIntentParser;
import com.roomfit.placement.FeedbackPlanInterpreter;
import com.roomfit.placement.PlacementService;
import com.roomfit.placement.ValidationService;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderIsolationConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeedbackIntentParserConfig feedbackConfig = new FeedbackIntentParserConfig();
    private final FeedbackPlanConfig feedbackPlanConfig = new FeedbackPlanConfig();
    private final PlacementServiceConfig placementConfig = new PlacementServiceConfig();
    private final MockProductRepository repository = new MockProductRepository();

    @Test
    void feedbackEnabledPlacementDisabledCreatesOnlyFeedbackPrimary() {
        LlmFeedbackProperties properties = rootConfigured();
        properties.setEnabled(true);
        properties.getPlacement().setEnabled(false);

        FallbackFeedbackIntentParser feedback = feedback(properties);
        FallbackFeedbackPlanInterpreter feedbackPlan = feedbackPlan(properties);
        FallbackPlacementService placement = placement(properties);

        assertThat(feedback.hasPrimaryParser()).isTrue();
        assertThat(feedbackPlan.hasPrimaryInterpreter()).isTrue();
        assertThat(placement.hasPrimaryService()).isFalse();
    }

    @Test
    void validFeedbackOverrideDoesNotEnablePlacementWhenRootPlacementConfigurationIsInvalid() {
        LlmFeedbackProperties properties = new LlmFeedbackProperties();
        properties.setEnabled(true);
        properties.getPlacement().setEnabled(true);
        properties.getFeedback().setApiKey("feedback-test-token");
        properties.getFeedback().setBaseUrl("https://feedback.example.test/v1");
        properties.getFeedback().setModel("feedback-test-model");

        assertThat(properties.hasValidFeedbackClientConfig()).isTrue();
        assertThat(properties.hasValidClientConfig()).isFalse();
        assertThat(feedback(properties).hasPrimaryParser()).isTrue();
        assertThat(feedbackPlan(properties).hasPrimaryInterpreter()).isTrue();
        assertThat(placement(properties).hasPrimaryService()).isFalse();
    }

    @Test
    void feedbackDisabledPlacementEnabledKeepsPlacementPrimaryAvailable() {
        LlmFeedbackProperties properties = rootConfigured();
        properties.setEnabled(false);
        properties.getPlacement().setEnabled(true);

        FallbackFeedbackIntentParser feedback = feedback(properties);
        FallbackFeedbackPlanInterpreter feedbackPlan = feedbackPlan(properties);
        FallbackPlacementService placement = placement(properties);

        assertThat(feedback.hasPrimaryParser()).isFalse();
        assertThat(feedbackPlan.hasPrimaryInterpreter()).isFalse();
        assertThat(placement.hasPrimaryService()).isTrue();
    }

    @Test
    void bothDisabledCreatesNoExternalProviderPrimary() {
        LlmFeedbackProperties properties = rootConfigured();
        properties.setEnabled(false);
        properties.getPlacement().setEnabled(false);

        assertThat(feedback(properties).hasPrimaryParser()).isFalse();
        assertThat(feedbackPlan(properties).hasPrimaryInterpreter()).isFalse();
        assertThat(placement(properties).hasPrimaryService()).isFalse();
    }

    @Test
    void invalidFeedbackOverrideDoesNotDisableValidPlacementConfiguration() {
        LlmFeedbackProperties properties = rootConfigured();
        properties.setEnabled(true);
        properties.getPlacement().setEnabled(true);
        properties.getFeedback().setApiKey("placeholder");

        assertThat(properties.hasValidFeedbackClientConfig()).isFalse();
        assertThat(properties.hasValidClientConfig()).isTrue();
        assertThat(feedback(properties).hasPrimaryParser()).isFalse();
        assertThat(feedbackPlan(properties).hasPrimaryInterpreter()).isFalse();
        assertThat(placement(properties).hasPrimaryService()).isTrue();
    }

    private LlmFeedbackProperties rootConfigured() {
        LlmFeedbackProperties properties = new LlmFeedbackProperties();
        properties.setApiKey("test-token");
        properties.setBaseUrl("https://llm.example.test/v1");
        properties.setModel("test-model");
        return properties;
    }

    private FallbackFeedbackIntentParser feedback(LlmFeedbackProperties properties) {
        FeedbackIntentParser parser = feedbackConfig.feedbackIntentParser(properties, objectMapper);
        return (FallbackFeedbackIntentParser) parser;
    }

    private FallbackFeedbackPlanInterpreter feedbackPlan(LlmFeedbackProperties properties) {
        FeedbackPlanInterpreter interpreter = feedbackPlanConfig.feedbackPlanInterpreter(properties, objectMapper);
        return (FallbackFeedbackPlanInterpreter) interpreter;
    }

    private FallbackPlacementService placement(LlmFeedbackProperties properties) {
        PlacementService service = placementConfig.placementService(properties, objectMapper,
                new MockProductService(repository, new PurchaseUrlHealthCheckService(repository)),
                new ProductRecommendationService(repository),
                new ValidationService());
        return (FallbackPlacementService) service;
    }
}
