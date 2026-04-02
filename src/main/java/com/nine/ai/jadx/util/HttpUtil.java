package com.nine.ai.jadx.util;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpUtil {
	private static final Logger LOG = LoggerFactory.getLogger(HttpUtil.class);
	private static final String CHARSET = StandardCharsets.UTF_8.name();
	// 单例实例
	private static final HttpUtil instance = new HttpUtil(true);
	private final boolean enableCors;
	private HttpUtil(boolean enableCors) {
		this.enableCors = enableCors;
	}

	public static HttpUtil getInstance() {
		return instance;
	}

	public void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
		if (exchange == null) return;

		// 处理 CORS OPTIONS 预检请求
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			if (enableCors) {
				exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
				exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
				exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
			}
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return;
		}

		// 防空指针保护
		String safeResponse = responseText == null ? "" : responseText;
		byte[] bytes = safeResponse.getBytes(StandardCharsets.UTF_8);
		String contentType = "text/plain; charset=utf-8";
		String trimRes = safeResponse.trim();
		if (trimRes.startsWith("{") || trimRes.startsWith("[")) {
			contentType = "application/json; charset=utf-8";
		}
		exchange.getResponseHeaders().set("Content-Type", contentType);

		if (enableCors) {
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		}

		try {
			exchange.sendResponseHeaders(statusCode, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		} finally {
			exchange.close();
		}
	}

	public Map<String, String> parseParams(String query) {
		Map<String, String> params = new HashMap<>();
		if (query == null || query.isEmpty()) return params;

		try {
			for (String s : query.split("&")) {
				if (s.isEmpty()) continue;
				String[] part = s.split("=", 2);
				String k = URLDecoder.decode(part[0], CHARSET).trim();
				String v = part.length > 1 ? URLDecoder.decode(part[1], CHARSET).trim() : "";
				params.put(k, v);
			}
		} catch (Exception e) {
			LOG.error("Parse params error", e);
		}
		return params;
	}
}
