package com.roomfit.config;

import com.roomfit.client.ClientScopeInterceptor;
import com.roomfit.client.ClientScopeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@EnableConfigurationProperties(ClientScopeProperties.class)
public class WebConfig implements WebMvcConfigurer {

    static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:5173,http://localhost:5174,http://localhost:5175";

    private final String[] allowedOrigins;
    private final ClientScopeInterceptor clientScopeInterceptor;

    public WebConfig(@Value("${CORS_ALLOWED_ORIGINS:" + DEFAULT_ALLOWED_ORIGINS + "}") String allowedOrigins,
                     ClientScopeInterceptor clientScopeInterceptor) {
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins);
        this.clientScopeInterceptor = clientScopeInterceptor;
    }

    static String[] parseAllowedOrigins(String configuredOrigins) {
        String origins = configuredOrigins == null || configuredOrigins.isBlank()
                ? DEFAULT_ALLOWED_ORIGINS
                : configuredOrigins;

        return Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*") // includes X-RoomFit-Client-Id for browser clients
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(clientScopeInterceptor)
                .addPathPatterns("/api/rooms/**", "/api/layouts/**", "/api/agent/**", "/api/clients/**", "/api/design-consultation/**");
    }
}
