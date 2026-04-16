package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AllClassHandler implements HttpHandler {
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
			String keyword = params.get("keyword");

			List<JavaClass> classes = decompiler.getClassesWithInners();
			if (keyword != null && !keyword.isBlank()) {
				String lowerKw = keyword.trim().toLowerCase();
				classes = classes.stream()
						.filter(c -> c.getFullName().toLowerCase().contains(lowerKw))
						.collect(Collectors.toList());
			}

			Map<String, Object> result = PageUtil.paginate(
					classes, offset, limit, "class-list", "classes", JavaClass::getFullName
			);
			http.sendResponse(exchange, 200, http.toJson(result));

		} catch (Exception e) {
			http.sendError(exchange, 500, e.getMessage());
		}
	}
}
