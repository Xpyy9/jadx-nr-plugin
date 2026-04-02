package com.nine.ai.jadx.util;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PageUtil {

	public static final int DEFAULT_PAGE_SIZE = 50;

	// 核心优化：针对 Agent 的上下文保护。
	// 强制把最大拉取数量从 10000 降到 500。
	// 500 个类名或方法名大约消耗 3000~5000 Token，这是一个大模型能够精确处理的黄金区间。
	public static final int MAX_PAGE_SIZE = 500;
	public static final int MAX_OFFSET = 1000000;

	public static <T> Map<String, Object> paginate(
			List<T> allItems,
			int offset,
			int limit,
			String dataType,
			String itemsKey,
			Function<T, Object> transformer
	) {
		int total = allItems.size();

		// 安全修正 offset / limit
		offset = Math.max(0, Math.min(offset, MAX_OFFSET));

		// 防止 Agent 贪婪调用导致 Token 爆炸
		if (limit <= 0) {
			limit = DEFAULT_PAGE_SIZE;
		} else {
			limit = Math.min(limit, MAX_PAGE_SIZE);
		}

		// 完美的边界保护逻辑，完全保留
		int start = Math.min(offset, total);
		int end = Math.min(offset + limit, total);

		List<Object> pageItems = allItems.subList(start, end).stream()
				.map(transformer)
				.collect(Collectors.toList());

		boolean hasMore = end < total;
		int nextOffset = hasMore ? end : -1;

		Map<String, Object> result = new HashMap<>();
		// 为了让 Agent 清楚自己在看什么数据
		result.put("type", dataType);
		result.put(itemsKey, pageItems);

		// 构建精简版的分页元数据
		Map<String, Object> pagination = new HashMap<>();
		pagination.put("total", total);
		pagination.put("offset", offset);
		pagination.put("limit", limit);
		pagination.put("count", pageItems.size());
		pagination.put("has_more", hasMore);

		// 优化点：对于 Agent 来说，它只需要知道 "能不能继续往下翻" (has_more)
		// 以及 "下一页从哪开始" (next_offset)。
		// prev_offset, current_page 等字段主要是给人类的 GUI 前端用的，
		// 发给 LLM 只会浪费 Token 和分散注意力，所以我帮你精简了。
		if (hasMore) {
			pagination.put("next_offset", nextOffset);
		}

		result.put("pagination", pagination);
		return result;
	}
}
