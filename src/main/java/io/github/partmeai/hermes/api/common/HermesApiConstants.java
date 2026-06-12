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

package io.github.partmeai.hermes.api.common;

import org.springframework.ai.observation.conventions.AiProvider;

/**
 * Common value constants for Hermes API.
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api">Hermes OpenAI HTTP API</a>
 */
public final class HermesApiConstants {

	public static final String DEFAULT_BASE_URL = "http://localhost:18789";

	public static final String PROVIDER_NAME = "hermes";

	private HermesApiConstants() {
	}
}
