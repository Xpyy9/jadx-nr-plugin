package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ClassHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ClassHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendError(exchange, 503, "Service unavailable");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String name = params.get("code_name");
		if (name == null || name.isBlank()) {
			name = params.get("class_name");
		}

		if (name == null || name.isBlank()) {
			http.sendError(exchange, 400, "Missing code_name parameter");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendError(exchange, 500, "Decompiler not available");
			return;
		}

		var cache = CodeUtil.initClassCache(decompiler);

		try {
			// 场景 1：带括号的精确签名 (如 com.app.Main.func(I)V)
			if (name.contains("(")) {
				int parenIndex = name.indexOf('(');
				int lastDot = name.lastIndexOf('.', parenIndex);
				if (lastDot > 0) {
					String className = name.substring(0, lastDot);
					String methodSig = name.substring(lastDot + 1);
					JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);
					if (cls != null) {
						JavaMethod mth = CodeUtil.findMethodBySig(cls, methodSig);
						if (mth != null) {
							sendMethodResponse(exchange, mth);
							return;
						}
					}
				}
			}

			// 场景 2：类名.方法名 (如 com.app.Main.onCreate)
			if (name.contains(".")) {
				int lastDot = name.lastIndexOf('.');
				String potentialClassName = name.substring(0, lastDot);
				String potentialMethodName = name.substring(lastDot + 1);

				JavaClass cls = CodeUtil.findClassDeeply(cache, potentialClassName, decompiler);
				if (cls != null) {
					JavaMethod mth = CodeUtil.findMethod(cls, potentialMethodName);
					if (mth != null) {
						sendMethodResponse(exchange, mth);
						return;
					}
				}
			}

			// 场景 3：整个字符串视为类名
			JavaClass targetClass = CodeUtil.findClassDeeply(cache, name, decompiler);
			if (targetClass != null) {
				sendClassResponse(exchange, targetClass);
				return;
			}

			http.sendError(exchange, 404, "Target not found: " + name);

		} catch (Exception e) {
			logger.error("ClassHandler failed", e);
			http.sendError(exchange, 500, "Internal Error: " + e.getMessage());
		}
	}

	private void sendClassResponse(HttpExchange exchange, JavaClass cls) throws IOException {
		String code = cls.getCode();
		if (code == null || code.isEmpty()) code = "/* Decompile failed */";
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "class");
		result.put("class_name", cls.getFullName());
		result.put("code", code);
		http.sendResponse(exchange, 200, http.toJson(result));
	}

	private void sendMethodResponse(HttpExchange exchange, JavaMethod mth) throws IOException {
		String code = mth.getCodeStr();
		if (code == null || code.isEmpty()) code = "/* Method decompile failed */";
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "method");
		result.put("method_name", mth.getFullName());
		result.put("code", code);
		http.sendResponse(exchange, 200, http.toJson(result));
	}
}
