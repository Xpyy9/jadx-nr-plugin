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

/**
 * 字符串搜索 — CodeIndex 就绪时同步返回 200，否则走异步 202。
 */
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

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

		// 缓存命中 → 同步返回
		List<String> cached = SEARCH_CACHE.get(query);
		if (cached != null) {
			logger.info("String search cache hit for: {}", query);
			Map<String, Object> response = PageUtil.paginate(
					cached, offset, limit, "string-search-results", "classes", item -> item
			);
			http.sendResponse(exchange, 200, http.toJson(response));
			return;
		}

		// CodeIndex 已就绪 → 同步搜索
		CodeIndexManager indexManager = CodeIndexManager.getInstance();
		if (indexManager.isIndexed()) {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					http.sendError(exchange, 500, "Decompiler not available");
					return;
				}

				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<String> results = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					if (entry.getValue().contains(query)) {
						results.add(entry.getKey());
					}
				}

				SEARCH_CACHE.put(query, results);
				logger.info("String search completed synchronously for '{}': {} results", query, results.size());

				Map<String, Object> response = PageUtil.paginate(
						results, offset, limit, "string-search-results", "classes", item -> item
				);
				http.sendResponse(exchange, 200, http.toJson(response));
			} catch (Exception e) {
				logger.error("Sync string search failed", e);
				http.sendError(exchange, 500, "String search error: " + e.getMessage());
			}
			return;
		}

		// CodeIndex 未就绪 → 异步构建 + 搜索，返回 202
		String taskId = TaskManager.createHighLoadTask("STRING_SEARCH");
		logger.info("CodeIndex not ready, started async string search task: {}", taskId);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					TaskManager.updateTask(taskId, "FAILED", "Decompiler not available");
					return;
				}

				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<String> results = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					if (entry.getValue().contains(query)) {
						results.add(entry.getKey());
					}
				}

				SEARCH_CACHE.put(query, results);
				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(results));
			} catch (Exception e) {
				logger.error("Async string search failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"CodeIndex building, search started\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
