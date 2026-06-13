package io.github.partmeai.hermes.management;

import java.util.List;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import java.util.Optional;


import io.github.partmeai.hermes.api.HermesApi;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages Hermes model discovery via the API Server's {@code GET /v1/models} endpoint.
 * <p>
 * Hermes exposes a single model: {@code hermes-agent} (or the profile name for
 * multi-profile setups). Use the returned id directly as the OpenAI {@code model}
 * value in chat completion requests.
 *
 * @author Loong Wan
 * @see <a href="https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server">Hermes API Server</a>
 */
@Slf4j
public class HermesModelManager {

	private final HermesApi api;

	public HermesModelManager(HermesApi api) { this.api = api; }

	/**
	 * List available model ids from the API server.
	 */
	public List<String> listModels() {
		try {
			HermesApi.ListModelResponse response = this.api.listModels();
			if (response.data() == null) return List.of();
			return response.data().stream().map(HermesApi.ModelData::id).toList();
		} catch (Exception e) {
			log.warn("Failed to list models from Hermes API: {}", e.getMessage());
			return List.of();
		}
	}

	public Optional<HermesApi.ModelResponse> getModel(String modelId) {
		try { return Optional.ofNullable(this.api.getModel(modelId)); }
		catch (Exception e) { log.warn("Failed to get model '{}': {}", modelId, e.getMessage()); return Optional.empty(); }
	}

	public boolean isModelAvailable(String modelId) { return listModels().contains(modelId); }

	/** The default model id: {@code hermes-agent}. */
	public String getDefaultModel() { return HermesApiConstants.DEFAULT_MODEL; }
}
