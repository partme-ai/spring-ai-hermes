package io.github.partmeai.hermes.autoconfigure;

import io.github.partmeai.hermes.HermesChatModel;
import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import io.github.partmeai.hermes.api.SseErrorHandler;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import io.github.partmeai.hermes.management.HermesModelManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;

/**
 * Spring Boot auto-configuration for the Hermes API Server integration.
 * <p>
 * Creates {@link HermesApi}, {@link HermesChatModel}, and {@link HermesModelManager}
 * beans when {@code spring.ai.hermes.enabled} is {@code true} (the default).
 */
@AutoConfiguration(after = { RestClientAutoConfiguration.class, WebClientAutoConfiguration.class })
@ConditionalOnClass({ HermesApi.class, HermesChatModel.class })
@ConditionalOnProperty(prefix = "spring.ai.hermes", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HermesAutoConfiguration.HermesProperties.class)
@Slf4j
public class HermesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HermesApi hermesApi(HermesProperties properties,
                               RestClient.Builder restClientBuilder,
                               WebClient.Builder webClientBuilder,
                               ObjectProvider<ResponseErrorHandler> responseErrorHandler,
                               ObjectProvider<SseErrorHandler> sseErrorHandler) {
        log.info("Creating HermesApi bean connected to {}", properties.getBaseUrl());
        return HermesApi.builder()
            .baseUrl(properties.getBaseUrl())
            .restClientBuilder(restClientBuilder.clone()
                .defaultHeader("Authorization", "Bearer " + properties.resolveApiKey()))
            .webClientBuilder(webClientBuilder.clone()
                .defaultHeader("Authorization", "Bearer " + properties.resolveApiKey()))
            .responseErrorHandler(responseErrorHandler.getIfAvailable(() -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER))
            .sseErrorHandler(sseErrorHandler.getIfAvailable(() -> SseErrorHandler.DEFAULT))
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public HermesChatModel hermesChatModel(HermesApi api,
                                           HermesProperties properties,
                                           ToolCallingManager toolCallingManager,
                                           ObservationRegistry observationRegistry) {
        HermesChatOptions defaultOptions = HermesChatOptions.builder()
            .model(properties.getModel())
            .hermesSessionKey(properties.getSessionKey())
            .hermesSessionId(properties.getSessionId())
            .build();
        log.info("Creating HermesChatModel bean (model={})", defaultOptions.getModel());
        return HermesChatModel.builder()
            .api(api)
            .defaultOptions(defaultOptions)
            .toolCallingManager(toolCallingManager)
            .observationRegistry(observationRegistry)
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public HermesModelManager hermesModelManager(HermesApi api) {
        return new HermesModelManager(api);
    }

    @ConfigurationProperties(prefix = "spring.ai.hermes")
    @Data
    public static class HermesProperties {

        /** API Server base URL. */
        private String baseUrl = HermesApiConstants.DEFAULT_BASE_URL;

        /** Bearer token for API Server auth. */
        private String apiServerKey = "";

        /** Model id (cosmetic — actual LLM configured server-side). */
        private String model = HermesApiConstants.DEFAULT_MODEL;

        /** Stable per-channel memory scoping key. Max 256 chars. */
        private String sessionKey;

        /** Transcript-scoped session identifier. */
        private String sessionId;

        public String resolveApiKey() {
            return apiServerKey != null ? apiServerKey : "";
        }
    }
}
