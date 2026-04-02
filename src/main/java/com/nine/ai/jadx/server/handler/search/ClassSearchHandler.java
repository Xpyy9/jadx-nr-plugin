package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.*;

public class ClassSearchHandler implements HttpHandler {
	/**
	 * 在整个应用程序中搜索包含特定关键字的类。你可以选择在“类名”或“源代码”中进行搜索。
	 * 此工具仅返回匹配的完整类名列表，以便你进一步使用 getClassCode 提取详情。
	 *
	 * 【性能与安全警告】：
	 * 1. 默认仅在类名 (class_name) 中搜索，速度极快。
	 * 2. 除非你明确知道自己在找什么（比如特定的加密算法标识或硬编码 Key），否则【极度不推荐】使用 search_in=code。
	 * 3. 如果必须使用 search_in=code，【强制要求】配合 package 参数使用（例如 package=com.tencent.mm），以缩小反编译范围，否则极易导致服务端崩溃或超时！
	 */
	private final HttpUtil http = HttpUtil.getInstance();

	// 搜索位置
	private enum SearchLocation {
		CLASS_NAME, CODE
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		// 修复参数不一致 Bug：统一使用 search_term
		String searchTerm = params.get("search_term");
		String packageFilter = params.get("package");
		String searchIn = params.get("search_in");

		// 参数校验
		if (searchTerm == null || searchTerm.isBlank()) {
			sendError(exchange, 400, "Missing required parameter: search_term");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			String lowerTerm = searchTerm.toLowerCase();
			List<JavaClass> allClasses = decompiler.getClassesWithInners();

			// 解析搜索范围
			Set<SearchLocation> locations = parseSearchLocations(searchIn);
			boolean applyPackageFilter = isValidPackageFilter(packageFilter);

			// 核心性能优化：执行单次遍历搜索，利用短路特性避免无谓的 getCode() 耗时反编译
			List<JavaClass> matches = searchOptimized(allClasses, lowerTerm, locations, packageFilter, applyPackageFilter);

			// 分页
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> result = PageUtil.paginate(
					matches,
					offset,
					limit,
					"class-list",
					"classes",
					JavaClass::getFullName
			);

			// 接入修复控制字符和嵌套 Map 支持的安全 JSON 序列化
			http.sendResponse(exchange, 200, toJsonObj(result));

		} catch (Exception e) {
			sendError(exchange, 500, "Search error: " + e.getMessage());
		}
	}

	private Set<SearchLocation> parseSearchLocations(String searchIn) {
		Set<SearchLocation> set = new HashSet<>();
		if (searchIn == null || searchIn.isBlank()) {
			// 如果不传，为了保护服务端性能，强烈建议默认只搜 CLASS_NAME
			// 如果你确信需要默认搜 CODE，可以改回 CODE，但这极易导致 Agent 把服务端搞挂
			set.add(SearchLocation.CLASS_NAME);
			return set;
		}
		String[] parts = searchIn.toLowerCase().split(",");
		for (String p : parts) {
			if (p.contains("class_name")) set.add(SearchLocation.CLASS_NAME);
			if (p.contains("code")) set.add(SearchLocation.CODE);
		}
		if (set.isEmpty()) set.add(SearchLocation.CLASS_NAME);
		return set;
	}

	// 核心重构：将双循环改为单循环短路求值，性能提升巨大
	private List<JavaClass> searchOptimized(
			List<JavaClass> allClasses,
			String term,
			Set<SearchLocation> locations,
			String packageFilter,
			boolean applyPackageFilter
	) {
		List<JavaClass> matches = new ArrayList<>();
		boolean searchName = locations.contains(SearchLocation.CLASS_NAME);
		boolean searchCode = locations.contains(SearchLocation.CODE);

		for (JavaClass cls : allClasses) {
			try {
				// 1. O(1) 级别的包过滤，最快剔除无关类
				if (applyPackageFilter && packageFilter != null && !cls.getFullName().startsWith(packageFilter)) {
					continue;
				}

				// 2. 优先进行 O(1) 级别的类名匹配
				if (searchName && cls.getFullName().toLowerCase().contains(term)) {
					matches.add(cls);
					continue; // 短路求值：类名命中了，直接跳过下面极其耗时的 CODE 反编译
				}

				// 3. 只有在指定了搜代码，且类名没命中的情况下，才进行 O(N) 级别的耗时代码匹配
				if (searchCode) {
					String code = cls.getCode();
					if (code != null && code.toLowerCase().contains(term)) {
						matches.add(cls);
					}
				}
			} catch (Exception ignored) {}
		}
		return matches;
	}

	// 过滤混淆包（p000/p001...）
	private boolean isValidPackageFilter(String pkg) {
		if (pkg == null) return false;
		return !pkg.matches("^p[0-9]{3}$");
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
