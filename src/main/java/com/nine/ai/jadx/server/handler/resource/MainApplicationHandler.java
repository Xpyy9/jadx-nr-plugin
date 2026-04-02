package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class MainApplicationHandler implements HttpHandler {
	/**
	 * 获取该应用程序“主包名”下所有核心业务类的 Java 源代码，支持分页。
	 * 工具会自动解析 AndroidManifest.xml 提取 package 属性，然后返回所有属于该包名下的类代码。
	 * 【使用建议】：当你想快速扫视应用的核心业务逻辑、网络请求封装或加密工具类，而不包含大量第三方 SDK (如 com.google, okhttp3) 时，此工具非常有效。
	 * 【注意】：每页返回的代码量可能非常大，请务必参考返回的 pagination 节点判断是否继续调用，默认 limit 为 20。
	 */
	private final HttpUtil http = HttpUtil.getInstance();
	// 缩减单类代码返回量，防止多个类合并时引发大模型 Token 爆炸
	private static final int MAX_CODE_LENGTH = 100000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			ResourceFile manifest = findAndroidManifest(decompiler);
			if (manifest == null) {
				sendError(exchange, 404, "AndroidManifest.xml not found");
				return;
			}

			String packageName = extractPackageName(manifest);
			if (packageName.isBlank()) {
				sendError(exchange, 404, "Package name not found in manifest");
				return;
			}

			// 仅过滤类对象，不在这里进行耗时的 getCode() 反编译操作
			List<JavaClass> classes = decompiler.getClassesWithInners().stream()
					.filter(cls -> cls.getFullName().startsWith(packageName))
					.collect(Collectors.toList());

			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), 20); // 此接口极耗 Token，默认 limit 设为 20 更安全

			// ================= 核心优化：懒加载反编译 =================
			// 在 paginate 的 transformer 中处理，只反编译当前页的类，性能提升百倍
			Map<String, Object> result = PageUtil.paginate(
					classes,
					offset,
					limit,
					"application-classes",
					"classes",
					cls -> {
						Map<String, Object> info = new HashMap<>();
						info.put("name", cls.getFullName());
						info.put("type", "code/java");
						try {
							String code = cls.getCode();
							if (code != null && code.length() > MAX_CODE_LENGTH) {
								code = code.substring(0, MAX_CODE_LENGTH) + "\n[TRUNCATED: Use getClassCode for full source]";
							}
							info.put("content", code != null ? code : "");
						} catch (Exception e) {
							info.put("content", "// Error retrieving code: " + e.getMessage());
						}
						return info;
					}
			);

			// 接入修复控制字符和嵌套 Map 支持的安全 JSON 序列化
			http.sendResponse(exchange, 200, toJsonObj(result));

		} catch (Exception e) {
			sendError(exchange, 500, "Error: " + e.getMessage());
		}
	}

	private ResourceFile findAndroidManifest(JadxDecompiler decompiler) {
		// 优化：使用缓存 O(1) 查找
		Map<String, ResourceFile> cache = JadxUtil.getResourceCache(decompiler);
		if (cache != null) {
			ResourceFile exactMatch = cache.get("AndroidManifest.xml");
			if (exactMatch != null) return exactMatch;

			for (ResourceFile res : cache.values()) {
				if (res.getOriginalName() != null && res.getOriginalName().endsWith("AndroidManifest.xml")) {
					return res;
				}
			}
		}
		return null;
	}

	private String extractPackageName(ResourceFile manifest) {
		try {
			String content = JadxUtil.getResourceContent(manifest);
			if (content == null) return "";

			int pkgStart = content.indexOf("package=\"");
			if (pkgStart == -1) return "";

			int pkgEnd = content.indexOf("\"", pkgStart + 9);
			if (pkgEnd == -1) return "";

			return content.substring(pkgStart + 9, pkgEnd);
		} catch (Exception e) {
			return "";
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

	// 核心修复：支持递归处理嵌套 Map 和 List，确保生成 100% 标准的 JSON 字符串
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
