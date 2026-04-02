package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MainApplicationHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(MainApplicationHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendResponse(exchange, 500, "Decompiler not available");
			return;
		}

		// 解析分页参数
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
		int limit = Integer.parseInt(params.getOrDefault("limit", "100"));

		try {
			// 1. 获取 AndroidManifest.xml
			ResourceFile manifestRes = decompiler.getResources().stream()
					.filter(res -> "AndroidManifest.xml".equals(res.getOriginalName()))
					.findFirst().orElse(null);

			if (manifestRes == null) {
				http.sendResponse(exchange, 404, "AndroidManifest.xml not found");
				return;
			}

			String xml = JadxUtil.getResourceContent(manifestRes);

			// 2. 提取主包名 (Package Name)
			String packageName = extractPackageName(xml);
			if (packageName.isEmpty()) {
				http.sendResponse(exchange, 404, "Could not identify package name from Manifest");
				return;
			}

			// 3. 筛选属于该包名的所有类（包含内部类）
			// 注意：使用 getClassesWithInners() 保证混淆后的内部类不被遗漏
			List<JavaClass> allMatchedClasses = decompiler.getClassesWithInners().stream()
					.filter(cls -> cls.getFullName().startsWith(packageName))
					.collect(Collectors.toList());

			int total = allMatchedClasses.size();

			// 4. 执行分页逻辑
			List<String> pagedClasses = allMatchedClasses.stream()
					.skip(offset)
					.limit(limit)
					.map(JavaClass::getFullName)
					.collect(Collectors.toList());

			// 5. 构建结构化响应
			Map<String, Object> response = new HashMap<>();
			response.put("package", packageName);
			response.put("total", total);
			response.put("offset", offset);
			response.put("limit", limit);
			response.put("has_more", offset + pagedClasses.size() < total);
			response.put("classes", pagedClasses);

			http.sendResponse(exchange, 200, http.toJson(response));

		} catch (Exception e) {
			logger.error("Failed to fetch Main Application classes", e);
			http.sendResponse(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * 从 Manifest XML 中快速稳健地提取 package 属性
	 */
	private String extractPackageName(String xml) {
		// 匹配 <manifest ... package="com.test.app" ...>
		Pattern p = Pattern.compile("<manifest[^>]+package\\s*=\\s*\"([^\"]+)\"");
		Matcher m = p.matcher(xml);
		return m.find() ? m.group(1) : "";
	}
}
