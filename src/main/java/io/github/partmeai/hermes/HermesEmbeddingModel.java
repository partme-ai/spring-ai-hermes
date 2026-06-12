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

package io.github.partmeai.hermes;

import java.util.List;
import java.util.Map;

import io.github.partmeai.hermes.api.HermesApi;
import io.github.partmeai.hermes.api.HermesChatOptions;
import io.github.partmeai.hermes.api.HermesModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.util.Assert;

/**
 * {@link EmbeddingModel} implementation for Hermes Gateway.
 * <p>
 * Uses the Hermes Gateway's {@code POST /v1/embeddings} endpoint with
 * agent-target model routing. The {@code model} field uses Hermes agent-target
 * ids ({@code hermes/default}, {@code hermes/<agentId>}).
 * Use {@code x-hermes-model} via {@link HermesChatOptions#setXOpenclawModel(String)}
 * to override the backend embedding model.
 *
 * @author Loong Wan
 * @see <a href="https://docs.hermes.ai/gateway/openai-http-api">Hermes OpenAI HTTP API</a>
 */
public class HermesEmbeddingModel implements EmbeddingModel {

	private static final Logger logger = LoggerFactory.getLogger(HermesEmbeddingModel.class);

	private final HermesApi api;

	private final HermesChatOptions defaultOptions;

	public HermesEmbeddingModel(HermesApi api, HermesChatOptions defaultOptions) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		this.api = api;
		this.defaultOptions = defaultOptions;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		Assert.notNull(request, "request must not be null");

		HermesChatOptions requestOptions = mergeOptions(request.getOptions());
		Map<String, String> headers = requestOptions.toHttpHeaders();

		HermesApi.EmbeddingsRequest apiRequest = new HermesApi.EmbeddingsRequest(
				requestOptions.getModel(),
				request.getInstructions(),
				null,
				null);

		HermesApi.EmbeddingsResponse apiResponse = this.api.embed(apiRequest, headers);

		List<Embedding> embeddings = List.of();
		if (apiResponse.data() != null) {
			embeddings = apiResponse.data().stream()
				.map(data -> {
					float[] vector = new float[data.embedding().size()];
					for (int i = 0; i < data.embedding().size(); i++) {
						vector[i] = data.embedding().get(i);
					}
					return new Embedding(vector, data.index());
				})
				.toList();
		}

		EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(
				apiResponse.model(),
				new org.springframework.ai.chat.metadata.DefaultUsage(
					apiResponse.usage() != null ? apiResponse.usage().promptTokens() : 0,
					apiResponse.usage() != null ? apiResponse.usage().completionTokens() : 0));

		return new EmbeddingResponse(embeddings, metadata);
	}

	@Override
	public float[] embed(String text) {
		Assert.notNull(text, "text must not be null");
		var response = call(new EmbeddingRequest(List.of(text), null));
		if (response.getResults().isEmpty()) {
			return new float[0];
		}
		return response.getResults().get(0).getOutput();
	}

	@Override
	public float[] embed(Document document) {
		Assert.notNull(document, "document must not be null");
		return embed(document.getText());
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		Assert.notNull(texts, "texts must not be null");
		return call(new EmbeddingRequest(texts, null)).getResults().stream()
			.map(Embedding::getOutput)
			.toList();
	}

	private HermesChatOptions mergeOptions(EmbeddingOptions runtimeOptions) {
		HermesChatOptions merged;
		if (runtimeOptions instanceof HermesChatOptions ocOpts) {
			merged = HermesChatOptions.fromOptions(ocOpts);
		}
		else {
			merged = HermesChatOptions.builder().build();
		}
		merged = ModelOptionsUtils.merge(merged, this.defaultOptions, HermesChatOptions.class);
		if (merged.getModel() == null || merged.getModel().isEmpty()) {
			merged.setModel(HermesModel.DEFAULT.id());
		}
		return merged;
	}

	@Override
	public int dimensions() {
		return 0;
	}

	public static final class Builder {

		private HermesApi api;

		private HermesChatOptions defaultOptions = HermesChatOptions.builder()
				.model(HermesModel.DEFAULT.id()).build();

		private Builder() {
		}

		public Builder api(HermesApi api) {
			this.api = api;
			return this;
		}

		public Builder defaultOptions(HermesChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public HermesEmbeddingModel build() {
			return new HermesEmbeddingModel(this.api, this.defaultOptions);
		}
	}
}
