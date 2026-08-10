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

/**
 * Hermes 流式工具调用碎片聚合器。
 *
 * <p>将同一工具调用窗口内分散到多个 SSE 数据块的名称、参数和元数据顺序合并，
 * 普通文本块则保持逐块输出。聚合结果仍使用 Hermes 原始响应模型，供上层统一转换为
 * Spring AI 的工具调用结构。</p>
 */
final class HermesStreamToolCallAggregator {

	/**
	 * 按工具调用边界聚合流式响应块。
	 *
	 * <p>遇到工具调用碎片后持续收集，直到带有结束原因的响应块关闭窗口；未进入工具
	 * 调用窗口的普通响应块各自形成窗口，因此仍可逐块向下游发布。</p>
	 *
	 * @param chunks Hermes 原始流式响应块
	 * @return 工具调用窗口被合并后的响应流
	 */
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

	/**
	 * 合并两个响应块，并优先保留当前块中的非空顶层元数据。
	 *
	 * @param previous 已累计的响应块
	 * @param current 当前响应块
	 * @return 合并后的响应；任一参数为空时返回另一参数
	 */
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

	/**
	 * 合并两个候选项的消息、增量消息和结束原因。
	 *
	 * @param previous 已累计候选项
	 * @param current 当前候选项
	 * @return 合并后的候选项
	 */
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

	/**
	 * 合并消息内容、工具调用和工具响应关联信息。
	 *
	 * @param previous 已累计消息
	 * @param current 当前消息碎片
	 * @return 合并后的消息
	 */
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

	/**
	 * 按工具调用索引或标识合并工具调用碎片。
	 *
	 * @param previous 已累计工具调用
	 * @param current 当前响应块中的工具调用碎片
	 * @return 按逻辑位置排列且不含空占位的工具调用列表
	 */
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

	/**
	 * 解析当前工具调用碎片在累计列表中的逻辑位置。
	 *
	 * @param fragment 当前工具调用碎片
	 * @param previous 已累计工具调用列表
	 * @return 显式索引、匹配标识的位置、新位置，或无标识碎片对应的最后位置
	 */
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

	/**
	 * 合并单个工具调用的标识、类型和函数信息。
	 *
	 * @param previous 已累计工具调用
	 * @param current 当前工具调用碎片
	 * @return 合并后的工具调用
	 */
	private Message.ToolCall merge(Message.ToolCall previous, Message.ToolCall current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		return new Message.ToolCall(prefer(current.index(), previous.index()),
				preferText(current.id(), previous.id()), prefer(current.type(), previous.type()),
				merge(previous.function(), current.function()));
	}

	/**
	 * 合并工具函数名称并顺序拼接参数字符串碎片。
	 *
	 * @param previous 已累计函数信息
	 * @param current 当前函数信息碎片
	 * @return 合并后的函数信息
	 */
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

	/**
	 * 读取响应的首个候选项。
	 *
	 * @param response Hermes 响应块
	 * @return 首个候选项；列表为空时返回 {@code null}
	 */
	private ChatResponse.Choice firstChoice(ChatResponse response) {
		return CollectionUtils.isEmpty(response.choices()) ? null : response.choices().get(0);
	}

	/**
	 * 合并消息内容；仅当两端均为字符串时执行拼接。
	 *
	 * @param previous 已累计内容
	 * @param current 当前内容碎片
	 * @return 拼接结果，或当前非空内容，或历史内容
	 */
	private Object appendContent(Object previous, Object current) {
		if (previous instanceof String previousText && current instanceof String currentText) {
			return previousText + currentText;
		}
		return Objects.nonNull(current) ? current : previous;
	}

	/**
	 * 将两个可空字符串按先后顺序拼接。
	 *
	 * @param previous 前置字符串
	 * @param current 后置字符串
	 * @return 将空值视为空串后的拼接结果
	 */
	private String append(String previous, String current) {
		return (Objects.isNull(previous) ? "" : previous) + (Objects.isNull(current) ? "" : current);
	}

	/**
	 * 在首选文本有实际内容时使用首选值，否则使用回退值。
	 *
	 * @param preferred 首选文本
	 * @param fallback 回退文本
	 * @return 选中的文本
	 */
	private String preferText(String preferred, String fallback) {
		return StringUtils.hasText(preferred) ? preferred : fallback;
	}

	/**
	 * 在首选值非空时使用首选值，否则使用回退值。
	 *
	 * @param preferred 首选值
	 * @param fallback 回退值
	 * @param <T> 值类型
	 * @return 选中的值
	 */
	private <T> T prefer(T preferred, T fallback) {
		return Objects.nonNull(preferred) ? preferred : fallback;
	}
}
