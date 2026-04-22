package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.HttpExchange;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * SmartSearch: tries class_name search first, auto-falls back to string search if no results.
 * Reduces executor iterations by eliminating the "search class -> empty -> search string" pattern.
 */
public class SmartSearchHandler {
	private static final Logger logger = LoggerFactory.getLogger(SmartSearchHandler.class);
	private static final HttpUtil http = HttpUtil.getInstance();

	public static void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String query = params.get("query");
		if (query == null || query.isBlank()) {
			query = params.get("class_name");
		}

		if (query == null || query.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: query");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			// Phase 1: class name search (fast, sync)
			String lowerQuery = query.toLowerCase();
			List<JavaClass> allClasses = decompiler.getClassesWithInners();
			List<String> classMatches = new ArrayList<>();
			for (JavaClass cls : allClasses) {
				try {
					if (cls.getFullName().toLowerCase().contains(lowerQuery)) {
						classMatches.add(cls.getFullName());
					}
				} catch (Exception ignored) {}
			}

			if (!classMatches.isEmpty()) {
				// Class name search has results, return immediately
				int offset = HttpUtil.parseInt(params.get("offset"), 0);
				int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
				Map<String, Object> paginated = PageUtil.paginate(
						classMatches, offset, limit, "smart-search", "results", item -> item
				);
				paginated.put("strategy", "class_name");
				paginated.put("query", query);
				http.sendResponse(exchange, 200, http.toJson(paginated));
				return;
			}

			// Phase 2: fallback to string search (async, uses code index)
			String taskId = TaskManager.createHighLoadTask("SMART_SEARCH");
			logger.info("SmartSearch class_name empty, falling back to string search for: {}", query);

			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
			final String finalQuery = query;

			CompletableFuture.runAsync(() -> {
				try {
					Map<String, String> codeIndex = CodeIndexManager.getInstance().getIndex(decompiler);
					List<String> stringMatches = new ArrayList<>();
					for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
						if (entry.getValue().contains(finalQuery)) {
							stringMatches.add(entry.getKey());
						}
					}

					Map<String, Object> paginated = PageUtil.paginate(
							stringMatches, offset, limit, "smart-search", "results", item -> item
					);
					paginated.put("strategy", "string");
					paginated.put("query", finalQuery);

					TaskManager.updateTask(taskId, "SUCCESS", http.toJson(paginated));
				} catch (Exception e) {
					logger.error("SmartSearch string fallback failed", e);
					TaskManager.updateTask(taskId, "FAILED", e.getMessage());
				}
			}, PluginServer.getAsyncPool());

			String response = String.format(
					"{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Class name search empty, falling back to string search\"}",
					taskId
			);
			http.sendResponse(exchange, 202, response);

		} catch (Exception e) {
			logger.error("SmartSearch failed", e);
			http.sendError(exchange, 500, "SmartSearch error: " + e.getMessage());
		}
	}
}
