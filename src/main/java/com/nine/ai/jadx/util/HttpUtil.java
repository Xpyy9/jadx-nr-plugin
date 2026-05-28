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
	private static class Holder {
		private static final HttpUtil INSTANCE = new HttpUtil();
	}

	private final Gson gson;

	private HttpUtil() {
		this.gson = new GsonBuilder()
				.disableHtmlEscaping()
				.create();
	}

	public static HttpUtil getInstance() {
		return Holder.INSTANCE;
	}

	public String toJson(Object obj) {
		return gson.toJson(obj);
	}

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
				// ignore decode failure
			}
		}
		return result;
	}

	private static String stripXmlTags(String s) {
		if (s == null || !s.contains("<")) return s;
		return s.replaceAll("<[^>]+>[^<]*</[^>]+>", "")
				.replaceAll("<[^>]+/>", "")
				.replaceAll("<[^>]+>", "")
				.trim();
	}

	public void sendResponse(HttpExchange exchange, int statusCode, String content) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	public void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put("error", message);
		errorMap.put("source", "jadx-plugin");
		sendResponse(exchange, statusCode, gson.toJson(errorMap));
	}

	public static String sanitizeAction(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		int lt = s.indexOf('<');
		if (lt > 0) {
			s = s.substring(0, lt).trim();
		}
		return s.isEmpty() ? null : s;
	}

	public static int parseInt(String s, int defaultValue) {
		if (s == null || s.isBlank()) return defaultValue;
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
