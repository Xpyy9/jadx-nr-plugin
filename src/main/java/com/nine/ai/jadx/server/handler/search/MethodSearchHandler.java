package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.TaskManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MethodSearchHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(MethodSearchHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private static final int MAX_CACHE_ENTRIES = 100;
	/** LRU cache for method search: methodName -> list of "class | signature" strings */
	private static final Map<String, List<String>> METHOD_CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
					return size() > MAX_CACHE_ENTRIES;
				}
			});

	public static void clearMethodCache() {
		METHOD_CACHE.clear();
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String methodName = params.get("method_name");

		if (methodName == null || methodName.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: method_name");
			return;
		}

		// Check cache
		List<String> cached = METHOD_CACHE.get(methodName.toLowerCase());
		if (cached != null) {
			logger.info("Method search cache hit for: {}", methodName);
			String taskId = TaskManager.createHighLoadTask("METHOD_SEARCH");
			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), com.nine.ai.jadx.util.PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> result = com.nine.ai.jadx.util.PageUtil.paginate(
					cached, offset, limit, "method-search-results", "methods", item -> item
			);
			TaskManager.updateTask(taskId, "SUCCESS", http.toJson(result));

			String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Result from cache\"}", taskId);
			http.sendResponse(exchange, 202, response);
			return;
		}

		// Cache miss — async search
		String taskId = TaskManager.createHighLoadTask("METHOD_SEARCH");
		logger.info("Started background method search task: {} for: {}", taskId, methodName);

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), com.nine.ai.jadx.util.PageUtil.DEFAULT_PAGE_SIZE);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					TaskManager.updateTask(taskId, "FAILED", "Decompiler not available");
					return;
				}

				List<String> resultSignatures = new ArrayList<>();
				String lowerMethod = methodName.toLowerCase();

				for (JavaClass cls : decompiler.getClassesWithInners()) {
					try {
						for (JavaMethod mth : cls.getMethods()) {
							if (mth.getName().toLowerCase().contains(lowerMethod)) {
								String signature = mth.getName();
								try {
									signature = mth.getMethodNode().getMethodInfo().getShortId();
								} catch (Exception ignored) {}
								resultSignatures.add(cls.getFullName() + " | " + signature);
							}
						}
					} catch (Exception ignored) {
					}
				}

				METHOD_CACHE.put(methodName.toLowerCase(), resultSignatures);

				Map<String, Object> result = com.nine.ai.jadx.util.PageUtil.paginate(
						resultSignatures, offset, limit, "method-search-results", "methods", item -> item
				);

				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(result));
			} catch (Exception e) {
				logger.error("Async method search failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Method search started\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
