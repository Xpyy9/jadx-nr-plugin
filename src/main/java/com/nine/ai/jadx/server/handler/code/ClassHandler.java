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
import java.util.Map;

public class ClassHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ClassHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String name = params.get("code_name");

		if (name == null || name.isBlank()) {
			http.sendResponse(exchange, 400, "Missing code_name parameter");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendResponse(exchange, 500, "Decompiler not available");
			return;
		}

		var cache = CodeUtil.initClassCache(decompiler);

		try {
			// ========================================================
			// 场景 1：带括号的精确签名 (如 com.app.Main.func(I)V)
			// ========================================================
			if (name.contains("(")) {
				int parenIndex = name.indexOf('(');
				int lastDot = name.lastIndexOf('.', parenIndex);
				if (lastDot > 0) {
					String className = name.substring(0, lastDot);
					String methodSig = name.substring(lastDot + 1);
					JavaClass cls = findClassDeeply(cache, className, decompiler);
					if (cls != null) {
						JavaMethod mth = findMethodBySig(cls, methodSig);
						if (mth != null) {
							sendMethodResponse(exchange, mth);
							return; // 找到精确方法，立即退出
						}
					}
				}
			}

			// ========================================================
			// 场景 2：尝试拆分最后一段作为函数名 (如 com.app.Main.onCreate)
			// 关键：只有当前半部分是类，且后半部分确实是该类方法时才触发
			// ========================================================
			if (name.contains(".")) {
				int lastDot = name.lastIndexOf('.');
				String potentialClassName = name.substring(0, lastDot);
				String potentialMethodName = name.substring(lastDot + 1);

				JavaClass cls = findClassDeeply(cache, potentialClassName, decompiler);
				if (cls != null) {
					JavaMethod mth = findMethodByName(cls, potentialMethodName);
					if (mth != null) {
						sendMethodResponse(exchange, mth);
						return; // 找到方法，立即退出
					}
				}
			}

			// ========================================================
			// 场景 3：尝试将整个字符串视为一个类 (如 com.app.MainActivity)
			// ========================================================
			JavaClass targetClass = findClassDeeply(cache, name, decompiler);
			if (targetClass != null) {
				sendClassResponse(exchange, targetClass);
				return; // 找到类，立即退出
			}

			// 全部落空
			http.sendResponse(exchange, 404, "Target not found: " + name);

		} catch (Exception e) {
			logger.error("ClassHandler failed", e);
			http.sendResponse(exchange, 500, "Internal Error: " + e.getMessage());
		}
	}

	private void sendClassResponse(HttpExchange exchange, JavaClass cls) throws IOException {
		String code = cls.getCode();
		if (code == null || code.isEmpty()) code = "/* Decompile failed */";
		http.sendResponse(exchange, 200, code);
	}

	private void sendMethodResponse(HttpExchange exchange, JavaMethod mth) throws IOException {
		String code = mth.getCodeStr();
		if (code == null || code.isEmpty()) code = "/* Method decompile failed */";
		http.sendResponse(exchange, 200, "// Method: " + mth.getFullName() + "\n\n" + code);
	}

	/**
	 * 深度查找类：处理点号与美元符的歧义
	 */
	private JavaClass findClassDeeply(Map<String, JavaClass> cache, String name, JadxDecompiler decompiler) {
		// 1. 原名查找
		JavaClass cls = CodeUtil.findClass(cache, name);
		if (cls != null) return cls;

		// 2. 内部类兼容处理 (将最后一段点号换成$)
		if (name.contains(".")) {
			int lastDot = name.lastIndexOf('.');
			String altName = name.substring(0, lastDot) + "$" + name.substring(lastDot + 1);
			cls = CodeUtil.findClass(cache, altName);
			if (cls != null) return cls;
		}

		// 3. 遍历兜底
		for (JavaClass jc : decompiler.getClasses()) {
			if (jc.getFullName().equals(name)) return jc;
		}
		return null;
	}

	private JavaMethod findMethodByName(JavaClass cls, String name) {
		for (JavaMethod m : cls.getMethods()) {
			if (m.getName().equals(name)) return m;
		}
		return null;
	}

	private JavaMethod findMethodBySig(JavaClass cls, String sig) {
		for (JavaMethod m : cls.getMethods()) {
			try {
				if (m.getMethodNode().getMethodInfo().getShortId().equals(sig)) return m;
			} catch (Exception ignored) {}
		}
		return null;
	}
}
