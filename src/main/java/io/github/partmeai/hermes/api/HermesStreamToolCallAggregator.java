package io.github.partmeai.hermes.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.partmeai.hermes.api.HermesApi.ChatResponse;
import io.github.partmeai.hermes.api.HermesApi.Message;
import reactor.core.publisher.Flux;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

final class HermesStreamToolCallAggregator {

	Flux<ChatResponse> aggregate(Flux<ChatResponse> chunks) {
		return Flux.defer(() -> {
			AtomicBoolean insideToolCall = new AtomicBoolean();
			return chunks
				.doOnNext(chunk -> {
					if (HermesApiHelper.isStreamingToolCall(chunk)) {
						insideToolCall.set(true);
					}
				})
				.windowUntil(chunk -> {
					if (insideToolCall.get() && HermesApiHelper.isStreamingDone(chunk)) {
						insideToolCall.set(false);
						return true;
					}
					return !insideToolCall.get();
				})
				.concatMap(window -> window.reduce(this::merge));
		});
	}

	ChatResponse merge(ChatResponse previous, ChatResponse current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		ChatResponse.Choice choice = merge(firstChoice(previous), firstChoice(current));
		return new ChatResponse(prefer(current.id(), previous.id()), prefer(current.object(), previous.object()),
				prefer(current.created(), previous.created()), prefer(current.model(), previous.model()),
				Objects.isNull(choice) ? List.of() : List.of(choice),
				prefer(current.usage(), previous.usage()));
	}

	private ChatResponse.Choice merge(ChatResponse.Choice previous, ChatResponse.Choice current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new ChatResponse.Choice(prefer(current.index(), previous.index()),
				merge(previous.message(), current.message()), merge(previous.delta(), current.delta()),
				prefer(current.finishReason(), previous.finishReason()));
	}

	private Message merge(Message previous, Message current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new Message(prefer(current.role(), previous.role()),
				appendContent(previous.content(), current.content()),
				mergeToolCalls(previous.toolCalls(), current.toolCalls()),
				prefer(current.toolCallId(), previous.toolCallId()),
				prefer(current.name(), previous.name()));
	}

	private List<Message.ToolCall> mergeToolCalls(List<Message.ToolCall> previous,
			List<Message.ToolCall> current) {
		if (CollectionUtils.isEmpty(previous)) {
			return current;
		}
		if (CollectionUtils.isEmpty(current)) {
			return previous;
		}

		List<Message.ToolCall> merged = new ArrayList<>(previous);
		for (Message.ToolCall fragment : current) {
			int index = resolveIndex(fragment, merged);
			while (merged.size() <= index) {
				merged.add(null);
			}
			merged.set(index, merge(merged.get(index), fragment));
		}
		return merged.stream().filter(Objects::nonNull).toList();
	}

	private int resolveIndex(Message.ToolCall fragment, List<Message.ToolCall> previous) {
		if (Objects.nonNull(fragment.index())) {
			return fragment.index();
		}
		if (StringUtils.hasText(fragment.id())) {
			for (int i = 0; i < previous.size(); i++) {
				Message.ToolCall candidate = previous.get(i);
				if (Objects.nonNull(candidate) && fragment.id().equals(candidate.id())) {
					return i;
				}
			}
			return previous.size();
		}
		return Math.max(previous.size() - 1, 0);
	}

	private Message.ToolCall merge(Message.ToolCall previous, Message.ToolCall current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		return new Message.ToolCall(prefer(current.index(), previous.index()),
				preferText(current.id(), previous.id()), prefer(current.type(), previous.type()),
				merge(previous.function(), current.function()));
	}

	private Message.ToolCallFunction merge(Message.ToolCallFunction previous,
			Message.ToolCallFunction current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new Message.ToolCallFunction(preferText(current.name(), previous.name()),
				append(previous.arguments(), current.arguments()));
	}

	private ChatResponse.Choice firstChoice(ChatResponse response) {
		return CollectionUtils.isEmpty(response.choices()) ? null : response.choices().get(0);
	}

	private Object appendContent(Object previous, Object current) {
		if (previous instanceof String previousText && current instanceof String currentText) {
			return previousText + currentText;
		}
		return Objects.nonNull(current) ? current : previous;
	}

	private String append(String previous, String current) {
		return (Objects.isNull(previous) ? "" : previous) + (Objects.isNull(current) ? "" : current);
	}

	private String preferText(String preferred, String fallback) {
		return StringUtils.hasText(preferred) ? preferred : fallback;
	}

	private <T> T prefer(T preferred, T fallback) {
		return Objects.nonNull(preferred) ? preferred : fallback;
	}
}
