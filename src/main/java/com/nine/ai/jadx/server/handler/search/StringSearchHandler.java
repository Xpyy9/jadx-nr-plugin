package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.*;
import jadx.api.JavaClass;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class StringSearchHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(StringSearchHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();
	private static final Map<String, List<String>> SEARCH_CACHE = new ConcurrentHashMap<>();
	private static final int MAX_CACHE_ENTRIES = 50;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String query = params.get("query");

		if (query == null || query.isBlank()) {
			http.sendResponse(exchange, 400, "{\"error\":\"Query parameter is required\"}");
			return;
		}
		if (SEARCH_CACHE.containsKey(query)) {
			logger.info("String search cache hit for: {}", query);
			String taskId = TaskManager.createHighLoadTask("STRING_SEARCH");
			String cachedResult = toJsonArray(SEARCH_CACHE.get(query));
			TaskManager.updateTask(taskId, "SUCCESS", cachedResult);

			String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Result from cache\"}", taskId);
			http.sendResponse(exchange, 202, response);
			return;
		}

		String taskId = TaskManager.createHighLoadTask("STRING_SEARCH");
		logger.info("Started background string search task: {}", taskId);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					TaskManager.updateTask(taskId, "FAILED", "Decompiler not available");
					return;
				}

				List<String> results = new ArrayList<>();
				for (JavaClass cls : decompiler.getClassesWithInners()) {
					String code = cls.getCode();
					if (code != null && code.contains(query)) {
						results.add(cls.getFullName());
					}
				}
				if (SEARCH_CACHE.size() >= MAX_CACHE_ENTRIES) {
					SEARCH_CACHE.clear(); // 简单清理策略
				}
				SEARCH_CACHE.put(query, results);

				TaskManager.updateTask(taskId, "SUCCESS", toJsonArray(results));
			} catch (Exception e) {
				logger.error("Async search failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		});

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Search started\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}

	private String toJsonArray(List<String> list) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
			if (i < list.size() - 1) sb.append(",");
		}
		sb.append("]");
		return sb.toString();
	}
}
