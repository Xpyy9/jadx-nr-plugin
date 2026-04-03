package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.Map;

public class SmaliHandler implements HttpHandler {
	// 获取传入类的smali代码 GET /getClassSmali?name=com.example.app.MainActivity
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String name = params.get("class_name");

		// 修复参数名提示 Bug，统一使用 'name'
		if (name == null || name.isBlank()) {
			http.sendResponse(exchange, 400, "Missing required parameter 'class_name'");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendResponse(exchange, 500, "Decompiler not available");
			return;
		}

		try {
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass targetClass = CodeUtil.findClass(cache, name);

			if (targetClass == null) {
				http.sendResponse(exchange, 404, "Class not found: " + name);
				return;
			}
			String smaliCode = targetClass.getSmali();
			if (smaliCode == null || smaliCode.trim().isEmpty()) {
				smaliCode = "# [WARNING] JADX failed to generate Smali code for this class. The Dex data might be corrupted.";
			}

			http.sendResponse(exchange, 200, smaliCode);

		} catch (Exception e) {
			http.sendResponse(exchange, 500, "# [ERROR] Internal error retrieving smali: " + e.getMessage());
		}
	}
}
