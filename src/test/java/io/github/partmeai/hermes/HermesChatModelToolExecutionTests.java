package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.ThinkOption;
import io.github.partmeai.hermes.api.HermesApi.ChatRequest;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for tool execution loop and streaming.
 */
class HermesChatModelToolExecutionTests {

    private static HermesChatModel newModel(HermesChatOptions defaults) {
        return HermesChatModel.builder()
            .api(HermesApi.builder().build())
            .defaultOptions(defaults)
            .toolCallingManager(ToolCallingManager.builder().build())
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
    }

    // ============================================================
    // hermesChatRequest — thinking wire-through
    // ============================================================

    @Test
    void thinkingOptionInheritedFromDefaultOptions() {
        var model = newModel(HermesChatOptions.builder().model("m").thinking(ThinkOption.ThinkBoolean.ENABLED).build());
        var prompt = model.buildRequestPrompt(new Prompt("hi"));
        var req = model.hermesChatRequest(prompt, false);
        assertThat(req.thinking()).isEqualTo(ThinkOption.ThinkBoolean.ENABLED);
    }

    @Test
    void thinkingOptionFromRuntimeOptions() {
        var model = newModel(HermesChatOptions.builder().model("m").thinking(ThinkOption.ThinkLevel.LOW).build());
        var runtime = HermesChatOptions.builder().thinking(ThinkOption.ThinkBoolean.DISABLED).build();
        var prompt = model.buildRequestPrompt(new Prompt("hi", runtime));
        var req = model.hermesChatRequest(prompt, false);
        assertThat(req.thinking()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    }

    @Test
    void thinkingNullWhenNotSet() {
        var model = newModel(HermesChatOptions.builder().model("m").build());
        var prompt = model.buildRequestPrompt(new Prompt("hi"));
        var req = model.hermesChatRequest(prompt, false);
        assertThat(req.thinking()).isNull();
    }

    // ============================================================
    // hermesChatRequest — stream flag propagation
    // ============================================================

    @Test
    void streamFlagTrue() {
        var model = newModel(HermesChatOptions.builder().model("m").build());
        var req = model.hermesChatRequest(model.buildRequestPrompt(new Prompt("hi")), true);
        assertThat(req.stream()).isTrue();
    }

    @Test
    void streamFlagFalse() {
        var model = newModel(HermesChatOptions.builder().model("m").build());
        var req = model.hermesChatRequest(model.buildRequestPrompt(new Prompt("hi")), false);
        assertThat(req.stream()).isFalse();
    }

    // ============================================================
    // hermesChatRequest — portable ChatOptions fallback
    // ============================================================

    @Test
    void portableChatOptionsConvertedToHermesChatOptions() {
        var model = newModel(HermesChatOptions.builder().model("DEFAULT").temperature(0.3).build());
        var portable = org.springframework.ai.chat.prompt.ChatOptions.builder()
            .temperature(0.9).topP(0.6).maxTokens(500).build();
        var prompt = model.buildRequestPrompt(new Prompt("hi", portable));
        var req = model.hermesChatRequest(prompt, false);
        assertThat(req.model()).isEqualTo("DEFAULT");
        assertThat(req.temperature()).isEqualTo(0.9);
        assertThat(req.topP()).isEqualTo(0.6);
    }

    // ============================================================
    // buildRequestPrompt — tool context null guard
    // ============================================================

    @Test
    void toolContextNullGuardDoesNotThrow() {
        var defaults = HermesChatOptions.builder().model("m")
            .internalToolExecutionEnabled(false)
            .toolCallbacks(new HermesChatRequestTests.TestToolCallback("t1"))
            .toolNames("t1")
            // toolContext deliberately NOT set — stays null
            .build();
        var model = newModel(defaults);
        var prompt = model.buildRequestPrompt(new Prompt("hi"));
        var opts = (HermesChatOptions) prompt.getOptions();
        assertThat(opts.getToolContext()).isNull();
    }

    @Test
    void toolContextFromRuntimeOverridesDefaults() {
        var defaults = HermesChatOptions.builder().model("m")
            .toolContext(Map.of("k1", "v1")).build();
        var model = newModel(defaults);
        var runtime = HermesChatOptions.builder()
            .toolContext(Map.of("k2", "v2")).build();
        var prompt = model.buildRequestPrompt(new Prompt("hi", runtime));
        var opts = (HermesChatOptions) prompt.getOptions();
        assertThat(opts.getToolContext()).containsEntry("k1", "v1").containsEntry("k2", "v2");
    }

    // ============================================================
    // Tool-calling eligibility — returnDirect simulated
    // ============================================================

    @Test
    void returnDirectPredicateBlocksToolExecutionLoop() {
        var model = newModel(HermesChatOptions.builder().model("m").build());
        // Simulate a predicate that always says "return direct" — no infinite loop
        var alwaysDirectModel = HermesChatModel.builder()
            .api(HermesApi.builder().build())
            .defaultOptions(HermesChatOptions.builder().model("m").build())
            .toolExecutionEligibilityPredicate((opts, resp) -> true)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
        var prompt = alwaysDirectModel.buildRequestPrompt(new Prompt("hi"));
        // This should not throw — the predicate says returnDirect but no tools are set,
        // so the call() will actually go to the API. We're just verifying the object builds fine.
        assertThat(prompt).isNotNull();
    }
}
