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
import java.util.*;

public class ClassStructureHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ClassStructureHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);
		String className = params.get("class_name");

		if (className == null || className.isBlank()) {
			httpUtil.sendError(exchange, 400, "Missing required parameter: class_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				httpUtil.sendError(exchange, 500, "Decompiler not available");
				return;
			}
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass targetClass = CodeUtil.findClass(cache, className);

			if (targetClass == null) {
				httpUtil.sendError(exchange, 404, "Class not found: " + className);
				return;
			}

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
					methods.add(method.getMethodNode().getMethodInfo().getShortId());
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

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("class_name", targetClass.getFullName());
			result.put("super_class", superClass);
			result.put("implements", interfaces);
			result.put("fields", fields);
			result.put("methods", methods);

			httpUtil.sendResponse(exchange, 200, httpUtil.toJson(result));

		} catch (Exception e) {
			logger.error("Get class structure error", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
