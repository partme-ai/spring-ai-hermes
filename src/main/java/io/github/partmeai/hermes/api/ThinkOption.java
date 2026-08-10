/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.partmeai.hermes.api;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 聊天模型思考选项。
 *
 * <p>控制模型是否或以何种等级在最终回答前执行推理。Qwen 3、DeepSeek-v3.1、
 * DeepSeek R1 等模型使用布尔开关，GPT-OSS 使用 {@code low}、{@code medium}、
 * {@code high} 字符串等级。该密封接口通过自定义 Jackson 组件直接映射为布尔值或字符串。</p>
 *
 * @author Mark Pollack
 * @since 1.1.0
 * @see ThinkBoolean
 * @see ThinkLevel
 */
@JsonSerialize(using = ThinkOption.ThinkOptionSerializer.class)
@JsonDeserialize(using = ThinkOption.ThinkOptionDeserializer.class)
public sealed interface ThinkOption {

	/**
	 * 将思考选项转换为协议中的 JSON 标量值。
	 *
	 * @return 布尔值或字符串等级
	 */
	Object toJsonValue();

	/**
	 * 思考选项 Jackson 序列化器。
	 *
	 * <p>将具体选项写成协议要求的原始布尔值、字符串或 JSON {@code null}，不引入对象包装层。</p>
	 */
	class ThinkOptionSerializer extends JsonSerializer<ThinkOption> {

		@Override
		/**
		 * 将思考选项写入 JSON 生成器。
		 *
		 * @param value 待序列化的思考选项，可以为 {@code null}
		 * @param gen Jackson JSON 生成器
		 * @param serializers 当前序列化上下文；方法签名要求传入，当前实现不直接使用
		 * @throws IOException 写入 JSON 失败时抛出
		 */
		public void serialize(ThinkOption value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
			if (value == null) {
				gen.writeNull();
			}
			else {
				gen.writeObject(value.toJsonValue());
			}
		}

	}

	/**
	 * 思考选项 Jackson 反序列化器。
	 *
	 * <p>根据当前 JSON 令牌构造布尔型或等级型选项，并拒绝协议未支持的令牌类型。</p>
	 */
	class ThinkOptionDeserializer extends JsonDeserializer<ThinkOption> {

		@Override
		/**
		 * 从当前 JSON 令牌读取思考选项。
		 *
		 * @param p 定位到待解析标量的 JSON 解析器
		 * @param ctxt 当前反序列化上下文；方法签名要求传入，当前实现不直接使用
		 * @return 对应的布尔型或等级型选项；JSON {@code null} 返回 {@code null}
		 * @throws IOException 当前令牌不是布尔值、字符串或 {@code null} 时抛出
		 */
		public ThinkOption deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			JsonToken token = p.currentToken();
			if (token == JsonToken.VALUE_TRUE) {
				return ThinkBoolean.ENABLED;
			}
			else if (token == JsonToken.VALUE_FALSE) {
				return ThinkBoolean.DISABLED;
			}
			else if (token == JsonToken.VALUE_STRING) {
				return new ThinkLevel(p.getValueAsString());
			}
			else if (token == JsonToken.VALUE_NULL) {
				return null;
			}
			throw new IOException("Cannot deserialize ThinkOption from token: " + token);
		}

	}

	/**
	 * 布尔型思考选项。
	 *
	 * <p>适用于仅支持启用或禁用思考的模型，例如 Qwen 3、DeepSeek-v3.1 和 DeepSeek R1。</p>
	 *
	 * @param enabled whether thinking is enabled
	 */
	record ThinkBoolean(boolean enabled) implements ThinkOption {

		/**
		 * Constant for enabled thinking.
		 */
		public static final ThinkBoolean ENABLED = new ThinkBoolean(true);

		/**
		 * Constant for disabled thinking.
		 */
		public static final ThinkBoolean DISABLED = new ThinkBoolean(false);

		@Override
		/**
		 * 返回布尔型 JSON 值。
		 *
		 * @return 是否启用思考
		 */
		public Object toJsonValue() {
			return this.enabled;
		}

	}

	/**
	 * 字符串等级型思考选项。
	 *
	 * <p>适用于要求显式思考等级的 GPT-OSS 模型，构造时只接受
	 * {@code low}、{@code medium}、{@code high} 或 {@code null}。</p>
	 *
	 * @param level the thinking level: "low", "medium", or "high"
	 */
	record ThinkLevel(String level) implements ThinkOption {

		/** 协议允许的显式思考等级。 */
		private static final List<String> VALID_LEVELS = List.of("low", "medium", "high");

		/**
		 * GPT-OSS 低思考等级。
		 */
		public static final ThinkLevel LOW = new ThinkLevel("low");

		/**
		 * GPT-OSS 中等思考等级。
		 */
		public static final ThinkLevel MEDIUM = new ThinkLevel("medium");

		/**
		 * GPT-OSS 高思考等级。
		 */
		public static final ThinkLevel HIGH = new ThinkLevel("high");

		/**
		 * 校验并创建思考等级。
		 *
		 * @throws IllegalArgumentException 等级非空且不属于 {@code low}、{@code medium}、{@code high} 时抛出
		 */
		public ThinkLevel {
			if (level != null && !VALID_LEVELS.contains(level)) {
				throw new IllegalArgumentException("think level must be one of " + VALID_LEVELS + ", got: " + level);
			}
		}

		@Override
		/**
		 * 返回字符串等级 JSON 值。
		 *
		 * @return 思考等级字符串
		 */
		public Object toJsonValue() {
			return this.level;
		}

	}

}
