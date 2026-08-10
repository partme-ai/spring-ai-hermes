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

package io.github.partmeai.hermes.aot;

import io.github.partmeai.hermes.api.ThinkOption;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import static org.springframework.ai.aot.AiRuntimeHints.findJsonAnnotatedClassesInPackage;

/**
 * Hermes 原生镜像运行时提示注册器。
 *
 * <p>扫描 Hermes 包中参与 JSON 映射的类型，并补充思考选项自定义序列化器和
 * 反序列化器的反射元数据，使相关类型在 Spring AOT 与 GraalVM 原生镜像环境中可用。</p>
 */
public class HermesRuntimeHints implements RuntimeHintsRegistrar {

	/**
	 * 注册 Hermes JSON 类型及自定义思考选项编解码器的反射提示。
	 *
	 * @param hints Spring AOT 运行时提示集合
	 * @param classLoader 扫描 Hermes 包时使用的类加载器；接口要求传入，当前实现不直接使用
	 */
	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		var mcs = MemberCategory.values();
		for (var tr : findJsonAnnotatedClassesInPackage("io.github.partmeai.hermes")) {
			hints.reflection().registerType(tr, mcs);
		}
		hints.reflection().registerType(ThinkOption.ThinkOptionDeserializer.class, mcs);
		hints.reflection().registerType(ThinkOption.ThinkOptionSerializer.class, mcs);
	}
}
