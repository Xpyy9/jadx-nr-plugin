package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.HttpExchange;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * SmartSearch: class_name 搜索优先（同步），无结果时自动回退到 string 搜索。
 * string 回退：CodeIndex 就绪时同步返回 200，否则异步 202。
 */
public class SmartSearchHandler {
	private static final Logger logger = LoggerFactory.getLogger(SmartSearchHandler.class);
	private static final HttpUtil http = HttpUtil.getInstance();

	public static void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String queryParam = params.get("query");
		if (queryParam == null || queryParam.isBlank()) {
			queryParam = params.get("class_name");
		}

		if (queryParam == null || queryParam.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: query");
			return;
		}

		final String query = queryParam;

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

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
				Map<String, Object> paginated = PageUtil.paginate(
						classMatches, offset, limit, "smart-search", "results", item -> item
				);
				paginated.put("strategy", "class_name");
				paginated.put("query", query);
				http.sendResponse(exchange, 200, http.toJson(paginated));
				return;
			}

			// Phase 2: fallback to string search
			logger.info("SmartSearch class_name empty, falling back to string search for: {}", query);

			CodeIndexManager indexManager = CodeIndexManager.getInstance();
			if (indexManager.isIndexed()) {
				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<String> stringMatches = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					if (entry.getValue().contains(query)) {
						stringMatches.add(entry.getKey());
					}
				}

				Map<String, Object> paginated = PageUtil.paginate(
						stringMatches, offset, limit, "smart-search", "results", item -> item
				);
				paginated.put("strategy", "string");
				paginated.put("query", query);
				logger.info("SmartSearch string fallback completed synchronously: {} results", stringMatches.size());
				http.sendResponse(exchange, 200, http.toJson(paginated));
				return;
			}

			// CodeIndex 未就绪 → 异步
			AsyncTaskHelper.submit("SMART_SEARCH", exchange, "CodeIndex building, string search started", () -> {
				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<String> stringMatches = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					if (entry.getValue().contains(query)) {
						stringMatches.add(entry.getKey());
					}
				}

				Map<String, Object> paginated = PageUtil.paginate(
						stringMatches, offset, limit, "smart-search", "results", item -> item
				);
				paginated.put("strategy", "string");
				paginated.put("query", query);
				return http.toJson(paginated);
			});

		} catch (Exception e) {
			logger.error("SmartSearch failed", e);
			http.sendError(exchange, 500, "SmartSearch error: " + e.getMessage());
		}
	}
}
