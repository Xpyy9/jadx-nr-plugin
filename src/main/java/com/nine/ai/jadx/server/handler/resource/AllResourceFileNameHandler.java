package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AllResourceFileNameHandler implements HttpHandler {
	/**
	获取 Android 应用程序中的所有资源文件路径列表（例如 AndroidManifest.xml, res/values/strings.xml, 以及 assets 目录下的各种配置文件等）。
			【重要提示】：
			1. 这是一个获取“文件路径列表”的雷达工具，它不返回文件内容。
			2. 由于 APK 中通常包含成千上万的图片 (.png, .jpg) 和无用的 UI 布局文件 (.xml)，为了避免上下文 Token 浪费和分析干扰，【强烈建议】使用 keyword 参数进行搜索过滤。
			3. 典型使用场景：
			- 找入口和权限：传入 keyword="AndroidManifest.xml"
			- 找前端代码或配置文件：传入 keyword="assets" 或 keyword=".json"
			- 找硬编码字符串：传入 keyword="strings.xml"
	 */
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
				http.sendResponse(exchange, 500, "{\"error\":\"Decompiler not available\"}");
				return;
			}

			// 1. 解析请求参数
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
			String keyword = params.get("keyword");

			String lowerKw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;
			List<String> resourceFileNames = extractResourceNames(decompiler, lowerKw);

			if (resourceFileNames.isEmpty()) {
				http.sendResponse(exchange, 404, "{\"error\":\"No resources found matching the criteria.\"}");
				return;
			}

			// 3. 分页处理
			Map<String, Object> result = PageUtil.paginate(
					resourceFileNames,
					offset,
					limit,
					"application-resources",
					"files",
					item -> item
			);

			// 4. 返回安全序列化后的 JSON
			http.sendResponse(exchange, 200, toJsonObj(result));

		} catch (Exception e) {
			http.sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
		}
	}

	/**
	 * 获取和过滤资源文件名
	 */
	private List<String> extractResourceNames(JadxDecompiler decompiler, String lowerKw) {
		List<ResourceFile> resourceFiles = decompiler.getResources();
		List<String> fileNames = new ArrayList<>();

		for (ResourceFile resFile : resourceFiles) {
			try {
				String name = resFile.getDeobfName();
				if (name != null && !name.isBlank()) {
					if (lowerKw == null || name.toLowerCase().contains(lowerKw)) {
						fileNames.add(name);
					}
				}
			} catch (Exception e) {
			}
		}
		return fileNames;
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
