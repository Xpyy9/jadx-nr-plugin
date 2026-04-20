package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class SmaliHandler implements HttpHandler {
	// 获取传入类的smali代码 GET /getClassSmali?name=com.example.app.MainActivity
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String name = params.get("class_name");

		// 修复参数名提示 Bug，统一使用 'name'
		if (name == null || name.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter 'class_name'");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendError(exchange, 500, "Decompiler not available");
			return;
		}

		try {
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass targetClass = CodeUtil.findClassDeeply(cache, name, decompiler);

			if (targetClass == null) {
				http.sendError(exchange, 404, "Class not found: " + name);
				return;
			}
			String smaliCode = targetClass.getSmali();
			if (smaliCode == null || smaliCode.trim().isEmpty()) {
				smaliCode = "# [WARNING] JADX failed to generate Smali code for this class. The Dex data might be corrupted.";
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("type", "smali");
			result.put("class_name", name);
			result.put("code", smaliCode);
			http.sendResponse(exchange, 200, http.toJson(result));

		} catch (Exception e) {
			http.sendError(exchange, 500, "Internal error retrieving smali: " + e.getMessage());
		}
	}
}
