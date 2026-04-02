package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AllClassHandler implements HttpHandler {
	// 可分页获取当前项目中的所有类名称  GET /getAllClasses?offset=0&limit=50
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendResponse(exchange, 500, "Decompiler not available");
				return;
			}

			// 解析请求参数
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
			String keyword = params.get("keyword"); // 新增：Agent 核心过滤参数

			// 获取所有类
			List<JavaClass> classes = decompiler.getClassesWithInners();
			if (keyword != null && !keyword.isBlank()) {
				String lowerKw = keyword.trim().toLowerCase();
				classes = classes.stream()
						.filter(c -> c.getFullName().toLowerCase().contains(lowerKw))
						.collect(Collectors.toList());
			}
			Map<String, Object> result = PageUtil.paginate(
					classes,
					offset,
					limit,
					"class-list",
					"classes",
					JavaClass::getFullName
			);
			http.sendResponse(exchange, 200, toJsonObj(result));

		} catch (Exception e) {
			http.sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
		}
	}

	private int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	private String toJsonObj(Object v) {
		if (v == null) return "null";

		if (v instanceof String) {
			return "\"" + ((String) v).replace("\"", "\\\"")
					.replace("\n", "\\n")
					.replace("\r", "\\r") + "\"";
		} else if (v instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			List<?> list = (List<?>) v;
			for (int i = 0; i < list.size(); i++) {
				sb.append(toJsonObj(list.get(i)));
				if (i < list.size() - 1) sb.append(",");
			}
			sb.append("]");
			return sb.toString();
		} else if (v instanceof Map) {
			StringBuilder sb = new StringBuilder("{");
			Map<?, ?> map = (Map<?, ?>) v;
			int i = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				sb.append("\"").append(entry.getKey()).append("\":").append(toJsonObj(entry.getValue()));
				if (i < map.size() - 1) sb.append(",");
				i++;
			}
			sb.append("}");
			return sb.toString();
		} else {
			return v.toString();
		}
	}
}
