package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 方法搜索 — 全同步。
 * 方法元数据已在类加载时就位，不触发反编译，无需异步。
 */
public class MethodSearchHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(MethodSearchHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private static final int MAX_CACHE_ENTRIES = 100;
	private static final Map<String, List<Map<String, String>>> METHOD_CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, String>>> eldest) {
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

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

		try {
			String cacheKey = methodName.toLowerCase();
			List<Map<String, String>> results = METHOD_CACHE.get(cacheKey);

			if (results == null) {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					http.sendError(exchange, 500, "Decompiler not available");
					return;
				}

				results = new ArrayList<>();
				String lowerMethod = methodName.toLowerCase();

				for (JavaClass cls : decompiler.getClassesWithInners()) {
					try {
						for (JavaMethod mth : cls.getMethods()) {
							if (mth.getName().toLowerCase().contains(lowerMethod)) {
								Map<String, String> entry = new LinkedHashMap<>();
								entry.put("class_name", cls.getFullName());
								entry.put("method_name", mth.getName());
								try {
									entry.put("method_signature", mth.getMethodNode().getMethodInfo().getShortId());
								} catch (Exception e) {
									entry.put("method_signature", mth.getName());
								}
								try {
									int flags = mth.getMethodNode().getAccessFlags().rawValue();
									entry.put("access_flags", decodeAccessFlags(flags));
								} catch (Exception ignored) {}
								results.add(entry);
							}
						}
					} catch (Exception ignored) {}
				}

				METHOD_CACHE.put(cacheKey, results);
				logger.info("Method search completed synchronously for '{}': {} results", methodName, results.size());
			} else {
				logger.info("Method search cache hit for: {}", methodName);
			}

			Map<String, Object> response = PageUtil.paginate(
					results, offset, limit, "method-search-results", "methods", item -> item
			);
			http.sendResponse(exchange, 200, http.toJson(response));

		} catch (Exception e) {
			logger.error("Method search failed", e);
			http.sendError(exchange, 500, "Method search error: " + e.getMessage());
		}
	}

	static String decodeAccessFlags(int flags) {
		List<String> parts = new ArrayList<>();
		if ((flags & 0x0001) != 0) parts.add("public");
		if ((flags & 0x0002) != 0) parts.add("private");
		if ((flags & 0x0004) != 0) parts.add("protected");
		if ((flags & 0x0008) != 0) parts.add("static");
		if ((flags & 0x0010) != 0) parts.add("final");
		if ((flags & 0x0020) != 0) parts.add("synchronized");
		if ((flags & 0x0100) != 0) parts.add("native");
		if ((flags & 0x0400) != 0) parts.add("abstract");
		return parts.isEmpty() ? "package-private" : String.join(" ", parts);
	}
}
