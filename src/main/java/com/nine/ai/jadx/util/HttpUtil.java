package com.nine.ai.jadx.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpUtil {
	private static HttpUtil instance;
	private final Gson gson;

	private HttpUtil() {
		// 使用 GsonBuilder 可以更灵活地配置，比如处理 HTML 转义
		this.gson = new GsonBuilder()
				.disableHtmlEscaping()
				.create();
	}

	public static synchronized HttpUtil getInstance() {
		if (instance == null) {
			instance = new HttpUtil();
		}
		return instance;
	}

	/**
	 * 将对象转换为 JSON 字符串
	 */
	public String toJson(Object obj) {
		return gson.toJson(obj);
	}

	/**
	 * 解析 URL 参数
	 */
	public Map<String, String> parseParams(String query) {
		Map<String, String> result = new HashMap<>();
		if (query == null || query.isEmpty()) {
			return result;
		}
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			int idx = pair.indexOf("=");
			try {
				String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) : pair;
				String value = idx > 0 && pair.length() > idx + 1
						? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";
				result.put(key, value);
			} catch (Exception e) {
				// 解码失败跳过
			}
		}
		return result;
	}

	/**
	 * 发送响应
	 */
	public void sendResponse(HttpExchange exchange, int statusCode, String content) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

		// 设置跨域和内容类型
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(204, -1);
			return;
		}

		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
