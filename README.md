# spring-ai-hermes

Spring AI 模型集成：将 Hermes Gateway 桥接到 Spring AI 的 `ChatModel` 和 `EmbeddingModel` 接口。

通过 Hermes Gateway 的 OpenAI 兼容端点实现：

- `POST /v1/chat/completions` → `ChatModel`（流式 + 非流式）
- `POST /v1/embeddings` → `EmbeddingModel`
- `GET /v1/models` → 模型列表
- `GET /v1/models/{id}` → 单个模型信息
- `POST /v1/responses` → OpenAI Responses API

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.hiwepy</groupId>
    <artifactId>spring-ai-hermes</artifactId>
    <version>2.7.x.20260527-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
spring:
  ai:
    hermes:
      base-url: http://localhost:18789
      gateway-auth-token: your-gateway-token
      model: hermes/default
```

**认证说明：** 需要在 `RestClient.Builder` 和 `WebClient.Builder` 中手动配置 `Authorization: Bearer <token>` 请求头。参考下方代码示例。

### 3. 注入使用

```java
@RestController
public class ChatController {

    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatModel.call(new Prompt(message))
            .getResult().getOutput().getText();
    }

    @GetMapping("/chat/stream")
    public Flux<String> chatStream(@RequestParam String message) {
        return chatModel.stream(new Prompt(message))
            .map(response -> response.getResult().getOutput().getText());
    }
}
```

## Hermes 特有功能

### Agent 目标路由

Hermes 将 `model` 字段解释为 agent 目标，而非原始模型 ID：

```java
// 使用默认 agent
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .build();

// 使用指定 agent
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/research")
    .build();
```

### 覆盖后端模型

通过 `x-hermes-model` HTTP 请求头覆盖 agent 使用的后端模型：

```java
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .xOpenclawModel("openai/gpt-5.4")  // → HTTP 请求头: x-hermes-model
    .build();
```

### 会话路由

```java
// 通过 user 字段派生稳定 session key（作为 JSON 请求体字段发送）
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .user("conv:my-conversation-id")
    .build();

// 通过 x-hermes-session-key 请求头显式控制
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .xOpenclawSessionKey("my-session-key")  // → HTTP 请求头: x-hermes-session-key
    .build();
```

### 通道上下文

```java
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .xOpenclawMessageChannel("slack")  // → HTTP 请求头: x-hermes-message-channel
    .build();
```

### 标准 OpenAI 采样参数

```java
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes/default")
    .temperature(0.7)
    .topP(0.9)
    .frequencyPenalty(0.5)
    .presencePenalty(0.3)
    .seed(42)
    .stop(List.of("END"))
    .maxTokens(2048)  // → JSON 请求体: max_completion_tokens
    .build();
```

## 配置属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `spring.ai.hermes.base-url` | `http://localhost:18789` | Gateway HTTP 根地址 |
| `spring.ai.hermes.gateway-auth-token` | | 控制面认证令牌 (`gateway.auth.token`) |
| `spring.ai.hermes.gateway-auth-password` | | 控制面密码 (`gateway.auth.password`) |
| `spring.ai.hermes.model` | `hermes/default` | 默认 agent 目标模型 |
| `spring.ai.hermes.connect-timeout-millis` | `15000` | 连接超时 |
| `spring.ai.hermes.read-timeout-millis` | `120000` | 读取超时 |
| `spring.ai.hermes.verify-ssl` | `true` | 是否校验 HTTPS 证书 |

## API 参考

### ChatRequest 请求体字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `model` | String | Agent 目标 ID（如 `hermes/default`） |
| `messages` | List\<Message\> | 对话消息列表 |
| `stream` | Boolean | 是否启用 SSE 流式响应 |
| `tools` | List\<Tool\> | 工具定义列表 |
| `tool_choice` | Object | 工具选择策略（`"auto"`/`"none"`/`"required"` 等） |
| `max_completion_tokens` | Integer | 最大输出 token 数 |
| `temperature` | Double | 采样温度 (0.0–2.0) |
| `top_p` | Double | 核采样概率 |
| `frequency_penalty` | Double | 频率惩罚 (-2.0–2.0) |
| `presence_penalty` | Double | 存在惩罚 (-2.0–2.0) |
| `seed` | Integer | 随机种子 |
| `stop` | Object | 停止序列（String 或 List\<String\>，最多 4 个） |
| `user` | String | 用户标识，用于派生稳定会话 key |

### HTTP 请求头（Hermes 特有）

| 请求头 | 对应 Builder 方法 | 说明 |
|--------|-----------------|------|
| `x-hermes-model` | `.xOpenclawModel(...)` | 覆盖 agent 的后端 provider/model |
| `x-hermes-session-key` | `.xOpenclawSessionKey(...)` | 显式会话路由 key |
| `x-hermes-message-channel` | `.xOpenclawMessageChannel(...)` | 综合通道上下文（如 `"slack"`） |
| `x-hermes-agent-id` | `.xOpenclawAgentId(...)` | 兼容性 agent-id 覆盖 |

### ChatResponse 响应字段（OpenAI 兼容格式）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 响应唯一标识 |
| `object` | String | 对象类型（`chat.completion` 或 `chat.completion.chunk`） |
| `created` | Long | Unix 时间戳 |
| `model` | String | 使用的模型 |
| `choices[].index` | Integer | 选项索引 |
| `choices[].message` | Message | 非流式：完整消息 |
| `choices[].delta` | Message | 流式：增量更新 |
| `choices[].finish_reason` | String | 结束原因（`stop`, `tool_calls` 等） |
| `usage` | Usage | token 使用统计 |

## 相关文档

- [OpenAI Chat Completions](https://docs.hermes.ai/gateway/openai-http-api)
- [OpenResponses API](https://docs.hermes.ai/gateway/openresponses-http-api)
- [Gateway Protocol](https://docs.hermes.ai/gateway/protocol)

## 依赖关系

- Spring Boot 3.4+
- Spring AI 1.0.0+
- JDK 17+
