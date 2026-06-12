# spring-ai-hermes

Spring AI 模型集成：将 Hermes API Server 桥接到 Spring AI 的 `ChatModel` 接口。

通过 Hermes API Server 的 OpenAI 兼容端点实现：

- `POST /v1/chat/completions` → `ChatModel`（流式 + 非流式）
- `POST /v1/responses` → Responses API（对话状态持久化）
- `GET /v1/models` → 模型发现
- `POST /v1/runs` → Runs API（长会话流式执行）
- `GET /health` + `/v1/capabilities` → 健康检查与能力发现

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.partmeai</groupId>
    <artifactId>spring-ai-hermes</artifactId>
    <version>3.5.x.20260612-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
spring:
  ai:
    hermes:
      base-url: http://localhost:8642
      api-server-key: your-api-key
      model: hermes-agent
```

> **认证说明：** 需要在 `RestClient.Builder` 和 `WebClient.Builder` 中手动配置 `Authorization: Bearer <key>` 请求头。

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

### 模型（纯展示性）

Hermes 的 `model` 字段被接受但不影响实际 LLM 选择——模型在服务端配置：

```java
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes-agent")  // 默认模型 ID
    .build();
```

### 会话与记忆

```java
// 长期记忆作用域（max 256 字符）
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes-agent")
    .hermesSessionKey("agent:main:webui:dm:user-42")   // → X-Hermes-Session-Key
    .build();

// 对话级别的会话标识
HermesChatOptions options = HermesChatOptions.builder()
    .model("hermes-agent")
    .hermesSessionId("transcript-alpha")                // → X-Hermes-Session-Id
    .build();
```

### 内联图片

用户消息的 `content` 支持数组格式（文本 + 图片）：

```java
var content = List.of(
    new HermesApi.Message.ContentPart("text", "这是什么？", null),
    new HermesApi.Message.ContentPart("image_url", null,
        new HermesApi.Message.ImageUrl("https://example.com/cat.png", "high"))
);

var msg = HermesApi.Message.builder(HermesApi.Message.Role.USER)
    .content(content)
    .build();
```

### Responses API（对话状态持久化）

```java
// 创建带服务端状态的对话
var req = new HermesApi.ResponseRequest("hermes-agent",
    "What files are in my project?", "You are a coding assistant.",
    null, null, true);
var resp = api.responses(req);

// 多轮对话：previous_response_id 保持上下文
var req2 = new HermesApi.ResponseRequest("hermes-agent",
    "Show me the README", null, resp.id(), null, null);
var resp2 = api.responses(req2);

// 或使用 conversation 参数自动链式
var req3 = new HermesApi.ResponseRequest("hermes-agent",
    "Run the tests", null, null, "my-project", null);
```

## API 参考

### ChatRequest 请求体字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `model` | String | 展示性模型 ID（`hermes-agent`），实际 LLM 服务端配置 |
| `messages` | List\<Message\> | 对话消息列表 |
| `stream` | Boolean | SSE 流式响应 |
| `tools` | List\<Tool\> | 工具定义 |
| `max_completion_tokens` | Integer | 最大输出 token 数 |
| `temperature` | Double | 采样温度 |
| `top_p` | Double | 核采样概率 |
| `frequency_penalty` | Double | 频率惩罚 |
| `presence_penalty` | Double | 存在惩罚 |
| `seed` | Integer | 随机种子 |
| `stop` | Object | 停止序列 |
| `user` | String | 用户标识 |

### HTTP 请求头

| 请求头 | Builder | 说明 |
|--------|---------|------|
| `X-Hermes-Session-Key` | `.hermesSessionKey(...)` | 长期记忆作用域（max 256 字符） |
| `X-Hermes-Session-Id` | `.hermesSessionId(...)` | 对话级会话标识 |

### 完整端点列表

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/chat/completions` | `chat()` / `streamingChat()` | 标准 Chat Completions |
| `/v1/responses` | `responses()` / `getResponse()` / `deleteResponse()` | Responses API |
| `/v1/models` | `listModels()` / `getModel()` | 模型发现 |
| `/v1/runs` | `createRun()` / `getRun()` / `stopRun()` / `approveRun()` | Runs API |
| `/v1/runs/{id}/events` | `streamRunEvents()` | 运行事件 SSE 流 |
| `/v1/capabilities` | `getCapabilities()` | 能力发现 |
| `/v1/skills` | `listSkills()` | 技能列表 |
| `/v1/toolsets` | `listToolsets()` | 工具集列表 |
| `/health` | `health()` / `healthDetailed()` | 健康检查 |

## 配置属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `spring.ai.hermes.base-url` | `http://localhost:8642` | API Server 地址 |
| `spring.ai.hermes.api-server-key` | | Bearer token |

## 依赖关系

- Spring Boot 3.4+
- Spring AI 1.1.7+
- JDK 17+
