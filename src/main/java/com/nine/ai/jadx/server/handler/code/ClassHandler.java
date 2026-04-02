package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;

public class ClassHandler implements HttpHandler {
	// 根据类名获取类代码或函数代码 GET /getClassCode?name=com.example.class
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String name = params.get("name"); // 例如: com.app.MyClass 或 com.app.MyClass.encrypt(Ljava/lang/String;)V

		if (name == null || name.isBlank()) {
			http.sendResponse(exchange, 400, "Missing name parameter");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendResponse(exchange, 500, "Decompiler not available");
			return;
		}

		var cache = CodeUtil.initClassCache(decompiler);

		try {
			// 1. 解析传入的 name，判断是请求类还是请求方法
			String className = name;
			String methodSig = null; // 包含参数的签名，例如 encrypt(Ljava/lang/String;)V
			String methodNameOnly = null; // 纯函数名，例如 encrypt

			// 如果包含括号，说明是精确的函数签名请求 (配合 ClassStructureHandler 返回的格式)
			int parenIndex = name.indexOf('(');
			if (parenIndex > 0) {
				int lastDot = name.lastIndexOf('.', parenIndex);
				if (lastDot > 0) {
					className = name.substring(0, lastDot);
					methodSig = name.substring(lastDot + 1); // 包含函数名和签名
					methodNameOnly = methodSig.substring(0, methodSig.indexOf('('));
				}
			} else {
				// 尝试兼容你原来的 CodeUtil.isMethodName 逻辑
				if (CodeUtil.isMethodName(name)) {
					className = CodeUtil.extractClassName(name);
					methodNameOnly = CodeUtil.extractMethodName(name);
				}
			}

			// 2. 查找类
			JavaClass clazz = CodeUtil.findClass(cache, className);
			if (clazz == null) {
				http.sendResponse(exchange, 404, "Class not found: " + className);
				return;
			}

			// 3. 提取代码逻辑
			if (methodNameOnly != null) {
				JavaMethod targetMethod = null;

				// 优先进行精确的签名匹配 (解决重载问题)
				if (methodSig != null) {
					for (JavaMethod m : clazz.getMethods()) {
						try {
							if (m.getMethodNode().getMethodInfo().getShortId().equals(methodSig)) {
								targetMethod = m;
								break;
							}
						} catch (Exception ignored) {}
					}
				}

				// 如果签名匹配失败，或者只传了函数名，降级为名字匹配
				if (targetMethod == null) {
					for (JavaMethod m : clazz.getMethods()) {
						if (methodNameOnly.equals(m.getName())) {
							targetMethod = m;
							break;
						}
					}
				}

				if (targetMethod == null) {
					http.sendResponse(exchange, 404, "Method not found: " + methodNameOnly + " in class " + className);
					return;
				}

				// 核心优化：直接使用 JADX 原生 API 获取单函数代码，避免复杂的字符串截取
				String methodCode;
				try {
					methodCode = targetMethod.getCodeStr();
				} catch (Exception e) {
					// 如果版本不支持，或者反编译失败，提示 Agent 切换策略
					methodCode = "/* [WARNING] Failed to decompile method cleanly. Try using /getClassSmali */\n" +
							"// Fallback string extraction may be needed if jadx version is old.";
				}

				http.sendResponse(exchange, 200, "// === METHOD: " + targetMethod.getName() + " ===\n\n" + methodCode);

			} else {
				// 返回整个类的代码
				String classCode = clazz.getCode();

				// 增强提示：如果整个类反编译失败，提醒大模型
				if (classCode == null || classCode.trim().isEmpty()) {
					classCode = "/* [WARNING] Class decompilation returned empty. Fallback to /getClassSmali */";
				}
				http.sendResponse(exchange, 200, classCode);
			}
		} catch (Exception e) {
			http.sendResponse(exchange, 500, "Error processing request: " + e.getMessage());
		}
	}
}
