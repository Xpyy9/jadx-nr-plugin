package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MethodSearchHandler implements HttpHandler {
	/**
	 * 在整个应用程序的所有类中，精确搜索具有特定名称的方法。
	 * 此工具执行的是结构化搜索（仅匹配方法声明），速度极快，不会进行全文代码搜索。
	 * 返回结果的格式为 "完整类名 | 方法签名"（例如：com.example.app.MainActivity | onCreate(Landroid/os/Bundle;)V）。
	 * 【行动指南】：当你在结果中找到了感兴趣的方法后，请复制该行的内容，并将类名和方法签名分别作为参数，传递给 getClassCode 工具，以精确获取该函数的 Java 源代码。
	 */
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String methodName = params.get("method_name");

		if (methodName == null || methodName.isBlank()) {
			sendError(exchange, 400, "Missing required parameter: method_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			List<String> resultSignatures = new ArrayList<>();
			String lowerMethod = methodName.toLowerCase();

			// ================= 核心优化：结构化精确匹配，拒绝全量反编译 =================
			for (JavaClass cls : decompiler.getClassesWithInners()) {
				try {
					// 直接遍历类的结构树，速度极快（O(1) 级别），不涉及底层 Smali 到 Java 的反编译
					for (JavaMethod mth : cls.getMethods()) {
						if (mth.getName().toLowerCase().contains(lowerMethod)) {
							String signature = mth.getName();
							try {
								// 尝试获取完整的短签名 (例如: onCreate(Landroid/os/Bundle;)V)
								signature = mth.getMethodNode().getMethodInfo().getShortId();
							} catch (Exception ignored) {}

							// 返回 类名 | 完整方法签名，方便 Agent 下一步直接调用精确提取
							resultSignatures.add(cls.getFullName() + " | " + signature);
						}
					}
				} catch (Exception ignored) {
					// 单个类解析失败，跳过
				}
			}

			// 增加分页保护，防止某些通用方法（如 init, onClick）返回上万条数据撑爆 Token
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> result = PageUtil.paginate(
					resultSignatures,
					offset,
					limit,
					"method-search-results",
					"methods",
					item -> item
			);

			http.sendResponse(exchange, 200, toJsonObj(result));

		} catch (Exception e) {
			sendError(exchange, 500, "Search error: " + e.getMessage());
		}
	}

	private int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
		http.sendResponse(exchange, code, "{\"error\":\"%s\"}".formatted(escapeJson(msg)));
	}

	private String escapeJson(String s) {
		return s == null ? "" : s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}

	// 保持与其他接口一致的安全 JSON 序列化
	private String toJsonObj(Object v) {
		if (v == null) return "null";
		if (v instanceof String) {
			return "\"" + escapeJson((String) v) + "\"";
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
