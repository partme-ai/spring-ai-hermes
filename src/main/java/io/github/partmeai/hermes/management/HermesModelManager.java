package io.github.partmeai.hermes.management;

import java.util.List;
import io.github.partmeai.hermes.api.common.HermesApiConstants;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.partmeai.hermes.api.HermesApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class HermesModelManager {

	private static final Logger logger = LoggerFactory.getLogger(HermesModelManager.class);

	private final HermesApi api;

	public HermesModelManager(HermesApi api) { this.api = api; }

	/**
	 * List available model ids from the API server.
	 */
	public List<String> listModels() {
		try {
			HermesApi.ListModelResponse response = this.api.listModels();
			if (response.data() == null) return List.of();
			return response.data().stream().map(HermesApi.ModelData::id).collect(Collectors.toList());
		} catch (Exception e) {
			logger.warn("Failed to list models from Hermes API: {}", e.getMessage());
			return List.of();
		}
	}

	public Optional<HermesApi.ModelResponse> getModel(String modelId) {
		try { return Optional.ofNullable(this.api.getModel(modelId)); }
		catch (Exception e) { logger.warn("Failed to get model '{}': {}", modelId, e.getMessage()); return Optional.empty(); }
	}

	public boolean isModelAvailable(String modelId) { return listModels().contains(modelId); }

	/** The default model id: {@code hermes-agent}. */
	public String getDefaultModel() { return HermesApiConstants.DEFAULT_MODEL; }
}
