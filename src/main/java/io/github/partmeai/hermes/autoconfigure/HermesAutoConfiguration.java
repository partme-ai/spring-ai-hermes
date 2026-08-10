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
 * Hermes API Server 的 Spring Boot 自动装配。
 *
 * <p>当 {@code spring.ai.hermes.enabled} 为 {@code true}（默认值）时，按缺失 Bean
 * 条件创建 {@link HermesApi}、{@link HermesChatModel} 和 {@link HermesModelManager}，
 * 并将 {@code spring.ai.hermes.*} 配置绑定到 {@link HermesProperties}。</p>
 */
@AutoConfiguration(after = { RestClientAutoConfiguration.class, WebClientAutoConfiguration.class })
@ConditionalOnClass({ HermesApi.class, HermesChatModel.class })
@ConditionalOnProperty(prefix = "spring.ai.hermes", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HermesAutoConfiguration.HermesProperties.class)
@Slf4j
public class HermesAutoConfiguration {

    /**
     * 创建 Hermes HTTP API 客户端。
     *
     * @param properties Hermes 连接与认证配置
     * @param restClientBuilder Spring 管理的同步 HTTP 客户端构建器
     * @param webClientBuilder Spring 管理的响应式 HTTP 客户端构建器
     * @param responseErrorHandler 可选的同步响应错误处理器
     * @param sseErrorHandler 可选的 SSE 解析异常处理器
     * @return 配置完成的 Hermes API 客户端
     */
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

    /**
     * Hermes 自动装配属性。
     *
     * <p>描述 API Server 连接、认证、默认模型以及 Hermes 会话作用域请求头所需的配置。</p>
     */
    @ConfigurationProperties(prefix = "spring.ai.hermes")
    @Data
    public static class HermesProperties {

        /** API Server 基础地址。 */
        private String baseUrl = HermesApiConstants.DEFAULT_BASE_URL;

        /** API Server Bearer Token；默认使用空字符串。 */
        private String apiServerKey = "";

        /** 请求模型标识；实际大模型由服务端配置。 */
        private String model = HermesApiConstants.DEFAULT_MODEL;

        /** 稳定的通道级长期记忆作用域键，发送前最多保留 256 个字符。 */
        private String sessionKey;

        /** 会话记录级标识。 */
        private String sessionId;

        /**
         * 解析发送给 API Server 的 Bearer Token。
         *
         * @return 已配置的 API Key；配置值为 {@code null} 时返回空字符串
         */
        public String resolveApiKey() {
            return apiServerKey != null ? apiServerKey : "";
        }
    }
}
    /**
     * 创建 Spring AI Hermes 聊天模型适配器。
     *
     * @param api Hermes HTTP API 客户端
     * @param properties 默认模型与会话配置
     * @param toolCallingManager Spring AI 工具调用管理器
     * @param observationRegistry Micrometer 观测注册表
     * @return 配置完成的 Hermes 聊天模型
     */
    /**
     * 创建 Hermes 模型发现管理器。
     *
     * @param api Hermes HTTP API 客户端
     * @return 使用默认缓存时长的模型管理器
     */
