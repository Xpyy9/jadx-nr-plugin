package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.*;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class StringSearchHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(StringSearchHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private static final int MAX_CACHE_ENTRIES = 100;
	private static final Map<String, List<String>> SEARCH_CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
					return size() > MAX_CACHE_ENTRIES;
				}
			});

	public static void clearSearchCache() {
		SEARCH_CACHE.clear();
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String query = params.get("query");

		if (query == null || query.isBlank()) {
			http.sendError(exchange, 400, "Query parameter is required");
			return;
		}

		List<String> cached = SEARCH_CACHE.get(query);
		if (cached != null) {
			logger.info("String search cache hit for: {}", query);
			String taskId = TaskManager.createHighLoadTask("STRING_SEARCH");
			TaskManager.updateTask(taskId, "SUCCESS", http.toJson(cached));

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

				// 使用全局代码索引，避免重复反编译
				Map<String, String> codeIndex = CodeIndexManager.getInstance().getIndex(decompiler);

				List<String> results = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					if (entry.getValue().contains(query)) {
						results.add(entry.getKey());
					}
				}

				SEARCH_CACHE.put(query, results);
				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(results));
			} catch (Exception e) {
				logger.error("Async search failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Search started\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
