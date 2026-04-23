package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.ClassStructureBuilder;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
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
			JavaClass targetClass = CodeUtil.findClassDeeply(cache, className, decompiler);

			if (targetClass == null) {
				httpUtil.sendError(exchange, 404, "Class not found: " + className);
				return;
			}

			Map<String, Object> result = ClassStructureBuilder.build(targetClass);
			httpUtil.sendResponse(exchange, 200, httpUtil.toJson(result));

		} catch (Exception e) {
			logger.error("Get class structure error", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
