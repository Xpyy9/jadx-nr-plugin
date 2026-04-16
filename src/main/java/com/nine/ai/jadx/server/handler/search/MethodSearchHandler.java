package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MethodSearchHandler implements HttpHandler {
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String methodName = params.get("method_name");

		if (methodName == null || methodName.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: method_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			List<String> resultSignatures = new ArrayList<>();
			String lowerMethod = methodName.toLowerCase();

			for (JavaClass cls : decompiler.getClassesWithInners()) {
				try {
					for (JavaMethod mth : cls.getMethods()) {
						if (mth.getName().toLowerCase().contains(lowerMethod)) {
							String signature = mth.getName();
							try {
								signature = mth.getMethodNode().getMethodInfo().getShortId();
							} catch (Exception ignored) {}
							resultSignatures.add(cls.getFullName() + " | " + signature);
						}
					}
				} catch (Exception ignored) {
				}
			}

			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> result = PageUtil.paginate(
					resultSignatures, offset, limit, "method-search-results", "methods", item -> item
			);

			http.sendResponse(exchange, 200, http.toJson(result));

		} catch (Exception e) {
			http.sendError(exchange, 500, "Search error: " + e.getMessage());
		}
	}
}
