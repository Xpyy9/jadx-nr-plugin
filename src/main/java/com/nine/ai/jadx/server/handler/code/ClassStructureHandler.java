package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClassStructureHandler implements HttpHandler {
	// 获取传入类的字段名和方法集合 GET /getClassStructure?class_name=com.example.class
	private static final Logger logger = LoggerFactory.getLogger(ClassStructureHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();

	public ClassStructureHandler() {
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);
		String className = params.get("class_name");

		if (className == null || className.isBlank()) {
			httpUtil.sendResponse(exchange, 400, "{\"error\":\"Missing required parameter: class_name\"}");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				httpUtil.sendResponse(exchange, 500, "{\"error\":\"Decompiler not available\"}");
				return;
			}
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass targetClass = CodeUtil.findClass(cache, className);

			if (targetClass == null) {
				httpUtil.sendResponse(exchange, 404, "{\"error\":\"Class not found: " + className + "\"}");
				return;
			}

			String classFullName = targetClass.getFullName();
			List<String> fields = new ArrayList<>();
			for (JavaField field : targetClass.getFields()) {
				try {
					String typeStr = field.getFieldNode().getType().toString();
					fields.add(typeStr + " " + field.getName());
				} catch (Exception e) {
					fields.add(field.getName());
				}
			}

			List<String> methods = new ArrayList<>();
			for (JavaMethod method : targetClass.getMethods()) {
				try {
					String methodSig = method.getMethodNode().getMethodInfo().getShortId();
					methods.add(methodSig);
				} catch (Exception e) {
					methods.add(method.getName());
				}
			}

			String superClass = "java.lang.Object";
			List<String> interfaces = new ArrayList<>();

			try {
				if (targetClass.getClassNode().getSuperClass() != null) {
					superClass = targetClass.getClassNode().getSuperClass().getObject();
				}
				if (targetClass.getClassNode().getInterfaces() != null) {
					for (jadx.core.dex.instructions.args.ArgType iface : targetClass.getClassNode().getInterfaces()) {
						interfaces.add(iface.getObject());
					}
				}
			} catch (Exception e) {
				logger.debug("Failed to get superclass/interfaces, using defaults.", e);
			}

			String json = "{"
					+ "\"class_name\":\"" + classFullName + "\","
					+ "\"super_class\":\"" + superClass + "\","
					+ "\"implements\":" + toJsonArray(interfaces) + ","
					+ "\"fields\":" + toJsonArray(fields) + ","
					+ "\"methods\":" + toJsonArray(methods)
					+ "}";

			httpUtil.sendResponse(exchange, 200, json);

		} catch (Exception e) {
			logger.error("Get class structure error", e);
			httpUtil.sendResponse(exchange, 500, "{\"error\":\"Internal error: " + e.getMessage() + "\"}");
		}
	}

	private String toJsonArray(List<String> list) {
		if (list == null || list.isEmpty()) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
			if (i < list.size() - 1) sb.append(",");
		}
		sb.append("]");
		return sb.toString();
	}
}
