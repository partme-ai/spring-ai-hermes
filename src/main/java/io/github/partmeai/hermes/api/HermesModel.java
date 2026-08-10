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

import org.springframework.ai.model.ChatModelDescription;
import io.github.partmeai.hermes.api.common.HermesApiConstants;

/**
 * Hermes 模型标识枚举。
 *
 * <p>Hermes 默认公开 {@code hermes-agent} 模型标识。请求中的 {@code model}
 * 字段用于协议兼容和配置档案标识，实际大模型仍由服务端配置；多配置档案场景下，
 * 服务端可将档案名称作为模型标识公开。</p>
 *
 * @since 1.0.0
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
public enum HermesModel implements ChatModelDescription {

	/**
 * 默认 Hermes Agent 模型标识。
	 */
	HERMES_AGENT(HermesApiConstants.DEFAULT_MODEL);

	/** 服务端协议使用的模型标识。 */
	private final String id;

	HermesModel(String id) {
		this.id = id;
	}

	/**
	 * 返回发送给 Hermes API 的模型标识。
	 *
	 * @return 当前枚举值对应的模型标识
	 */
	public String id() {
		return this.id;
	}

	@Override
	/**
	 * 返回 Spring AI 模型描述所需的模型名称。
	 *
	 * @return 当前枚举值对应的模型名称
	 */
	public String getName() {
		return this.id;
	}
}
