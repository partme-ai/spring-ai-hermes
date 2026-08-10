<a id="readme-top"></a>

<div align="center">

# spring-ai-hermes

**Spring Boot Starter for spring-ai-hermes**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.partmeai/spring-ai-hermes)](https://github.com/partme-ai/spring-ai-hermes)
[![Java](https://img.shields.io/badge/Java-17-orange)](#3-requirements-and-compatibility)
[![License](https://img.shields.io/badge/license-Apache-2.0-green)](https://www.apache.org/licenses/LICENSE-2.0)

[简体中文](./README.zh-CN.md) | [English](./README.md)

[Positioning](#1-positioning) · [Capabilities](#2-core-capabilities) ·
[Dependency](#5-dependency) · [Quick Start](#6-quick-start) ·
[Configuration](#7-configuration-reference) · [Versions](#9-version-lines-and-compatibility) ·
[Build](#10-build-and-test) · [License](#12-license)

</div>

---

> **Current Version**：`1.0.x.20260630-SNAPSHOT`<br>
> **JDK Baseline**：`17`<br>
> **Group ID**：`io.github.partmeai`<br>
> **Artifact ID**：`spring-ai-hermes`<br>
> **License**：Apache License 2.0<br>

## 1. Positioning

**spring-ai-hermes** is a Spring Boot starter that integrates **spring-ai-hermes** for applications using spring-ai-hermes. It provides auto-configuration, property binding, and ready-to-use beans so that applications can consume spring-ai-hermes capabilities with minimal setup.

| Dimension | Description |
|---|---|
| Type | Spring Boot Starter |
| Consumers | Spring Boot applications using spring-ai-hermes |
| Core Capabilities | auto-configuration, property binding, ready-to-use beans for spring-ai-hermes |
| JDK | `17` |
| Coordinates | `io.github.partmeai:spring-ai-hermes:1.0.x.20260630-SNAPSHOT` |
| Config Prefix | `spring.ai.hermes` |

## 2. Core Capabilities

| Capability | Status | Description |
|---|:---:|---|
| Auto-configuration | ✅ Stable | Registers spring-ai-hermes beans automatically |
| Property Binding | ✅ Stable | Binds `spring.ai.hermes.*` to `Properties` |
| `HermesApi` bean | ✅ Stable | Auto-registered via HermesAutoConfiguration |

## 3. Requirements and Compatibility

| Dependency | Minimum | Evidence |
|---|---:|---|
| JDK | `17` | `pom.xml` |
| Spring AI | `1.1.7` | `spring-ai-bom` |
| Spring Boot | `3.5.5` | `spring-boot-dependencies` |
| Maven | `3.6+` | Maven Enforcer |

## 4. Auto-configuration

The starter auto-configures the following beans:

| Bean | Condition | Missing Behavior |
|---|---|---|
| `HermesApi` | classpath + property | not created |
| `HermesChatModel` | classpath + property | not created |
| `HermesModelManager` | classpath + property | not created |

Auto-configuration registration:

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 2.7+ / 3.x / 4.x)
- `META-INF/spring.factories` (Spring Boot 2.x legacy)

## 5. Dependency

```xml
<dependency>
    <groupId>io.github.partmeai</groupId>
    <artifactId>spring-ai-hermes</artifactId>
    <version>1.0.x.20260630-SNAPSHOT</version>
</dependency>
```

No additional easy4j component dependencies.

## 6. Quick Start

### 6.1 Add dependency

Add the dependency above to your `pom.xml`.

### 6.2 Configure

```yaml
spring.ai.hermes:
  enabled: true
```

### 6.3 Use the bean

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Then inject the auto-configured bean in your code:

```java
@Autowired
private HermesApi hermesApi;
```

## 7. Configuration Reference

### 7.1 Config Prefix

`spring.ai.hermes`

### 7.2 Configuration Items

| Property | Type | Default | Required | Description | Sensitive |
|---|---|---|:---:|---|:---:|
| `spring.ai.hermes.enabled` | boolean | `true` | No | Enable the starter | No |
<!-- additional properties below -->

## 8. Version Lines and Compatibility

| Branch | JDK | Spring Boot | Component Version | Status |
|---|---:|---:|---|:---:|
| `feature/1.0.x` | `17` | 3.5.x / Spring AI 1.1.7 | `1.0.x.20260630-SNAPSHOT` | Current |
| `feature/2.0.x` | `17` | 4.0.x–4.1.x / Spring AI 2.0.0 | `2.0.x.20260630-SNAPSHOT` | Active |

## 9. Build and Test

```bash
mvn clean verify
mvn -pl spring-ai-hermes -am test
```

## 10. Troubleshooting

| Symptom | Diagnosis | Resolution |
|---|---|---|
| Bean not created | Check auto-configuration report | Verify `spring.ai.hermes.enabled=true` and classpath |
| `ClassNotFoundException` | Missing dependency | Add the required module |
| Version conflict | `mvn dependency:tree` | Use BOM for version alignment |

## 11. Contribution

1. Fork the repository.
2. Create a feature branch.
3. Run `mvn clean verify` before submitting.
4. Submit a pull request.

## 12. License

This project is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

---

<div align="center">

[Back to top](#readme-top) · [Issues](https://github.com/easy-4-java/spring-ai-hermes/issues) · [Repository](https://github.com/easy-4-java/spring-ai-hermes)

</div>
