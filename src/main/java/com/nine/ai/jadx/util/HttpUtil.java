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
	 * 解析 URL 参数，自动清除 LLM 可能注入的 XML 标签污染
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
				result.put(stripXmlTags(key), stripXmlTags(value));
			} catch (Exception e) {
				// 解码失败跳过
			}
		}
		return result;
	}

	/**
	 * 清除字符串中的 XML-like 标签及其内容。
	 * LLM 偶尔在参数中混入 &lt;arg_key&gt;...&lt;/arg_key&gt;&lt;arg_value&gt;...&lt;/arg_value&gt; 等标签。
	 */
	private static String stripXmlTags(String s) {
		if (s == null || !s.contains("<")) return s;
		// 移除 <tag>content</tag> 模式和自闭合 <tag/> 模式
		return s.replaceAll("<[^>]+>[^<]*</[^>]+>", "")
				.replaceAll("<[^>]+/>", "")
				.replaceAll("<[^>]+>", "")
				.trim();
	}

	/**
	 * 发送响应（CORS 已由 PluginServer.wrap() 统一处理）
	 */
	public void sendResponse(HttpExchange exchange, int statusCode, String content) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	/**
	 * 发送标准化 JSON 错误响应
	 */
	public void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
		String json = "{\"error\":\"" + escapeJson(message) + "\",\"source\":\"jadx-plugin\"}";
		sendResponse(exchange, statusCode, json);
	}

	/**
	 * JSON 字符串安全转义（完整处理所有控制字符）
	 */
	public static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}

	/**
	 * 清洗 action 参数：LLM 可能在 action 值中混入 XML-like 标签或多余内容，
	 * 仅保留第一个 '&lt;' 之前的纯文本作为真实 action 名。
	 */
	public static String sanitizeAction(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		int lt = s.indexOf('<');
		if (lt > 0) {
			s = s.substring(0, lt).trim();
		}
		return s.isEmpty() ? null : s;
	}

	/**
	 * 安全解析整数参数
	 */
	public static int parseInt(String s, int defaultValue) {
		if (s == null || s.isBlank()) return defaultValue;
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
