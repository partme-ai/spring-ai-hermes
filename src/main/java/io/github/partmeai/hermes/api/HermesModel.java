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

/**
 * Hermes model identifier.
 * <p>
 * Hermes exposes a single model: {@code hermes-agent}. The {@code model} field
 * in API requests is accepted but cosmetic — the actual LLM is configured
 * server-side. For multi-profile setups, each profile advertises its name
 * as the model id.
 *
 * @since 1.0.0
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
public enum HermesModel implements ChatModelDescription {

	/**
	 * The default Hermes agent model id.
	 */
	HERMES_AGENT("hermes-agent");

	private final String id;

	HermesModel(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	@Override
	public String getName() {
		return this.id;
	}
}
