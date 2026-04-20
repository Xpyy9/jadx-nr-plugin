package com.nine.ai.jadx.util;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PageUtil {

	public static final int DEFAULT_PAGE_SIZE = 50;
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

		offset = Math.max(0, Math.min(offset, MAX_OFFSET));
		if (limit <= 0) {
			limit = DEFAULT_PAGE_SIZE;
		} else {
			limit = Math.min(limit, MAX_PAGE_SIZE);
		}

		int start = Math.min(offset, total);
		int end = Math.min(offset + limit, total);

		List<Object> pageItems = allItems.subList(start, end).stream()
				.map(transformer)
				.collect(Collectors.toList());

		boolean hasMore = end < total;

		Map<String, Object> result = new HashMap<>();
		result.put("type", dataType);
		result.put(itemsKey, pageItems);

		Map<String, Object> pagination = new HashMap<>();
		pagination.put("total", total);
		pagination.put("offset", offset);
		pagination.put("limit", limit);
		pagination.put("count", pageItems.size());
		pagination.put("has_more", hasMore);
		if (hasMore) {
			pagination.put("next_offset", end);
		}

		result.put("pagination", pagination);
		return result;
	}
}
