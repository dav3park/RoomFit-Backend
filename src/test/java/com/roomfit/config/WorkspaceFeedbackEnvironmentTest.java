package com.roomfit.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFeedbackEnvironmentTest {
    @Test
    void feedbackEnvironmentNamesBindWithoutChangingPlacementCredentials() {
        runner(Map.of("ROOMFIT_LLM_FEEDBACK_ENABLED", "true", "ROOMFIT_LLM_FEEDBACK_API_KEY", "test-key",
                "ROOMFIT_LLM_FEEDBACK_BASE_URL", "https://api.openai.com/v1", "ROOMFIT_LLM_FEEDBACK_MODEL", "gpt-4o-mini",
                "ROOMFIT_LLM_FEEDBACK_TIMEOUT_MS", "15000"))
                .run(context -> {
                    LlmFeedbackProperties properties = context.getBean(LlmFeedbackProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.hasValidFeedbackClientConfig()).isTrue();
                    assertThat(properties.feedbackClientProperties().getApiKey()).isEqualTo("test-key");
                    assertThat(properties.feedbackClientProperties().getModel()).isEqualTo("gpt-4o-mini");
                    assertThat(properties.feedbackClientProperties().getTimeoutMs()).isEqualTo(15000);
                    assertThat(properties.getApiKey()).isEmpty();
                    assertThat(properties.getPlacement().isEnabled()).isFalse();
                });
    }

    @Test
    void missingEnvironmentKeepsFeedbackDisabledAndLegacyFallbackIntact() {
        runner(Map.of()).withPropertyValues("roomfit.llm.api-key=legacy-test-key", "roomfit.llm.base-url=https://example.com/v1", "roomfit.llm.model=legacy")
                .run(context -> {
                    LlmFeedbackProperties properties = context.getBean(LlmFeedbackProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.feedbackClientProperties().getApiKey()).isEqualTo("legacy-test-key");
                });
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withInitializer(context -> {
            context.getEnvironment().getPropertySources().remove("systemEnvironment");
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("testEnvironment", environment));
            try {
                new YamlPropertySourceLoader().load("application", new FileSystemResource("src/main/resources/application.yml"))
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException error) { throw new IllegalStateException(error); }
        });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmFeedbackProperties.class)
    static class Config {}
}
