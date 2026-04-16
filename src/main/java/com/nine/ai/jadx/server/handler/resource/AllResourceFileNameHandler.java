package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AllResourceFileNameHandler implements HttpHandler {
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

			String lowerKw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;
			List<String> resourceFileNames = extractResourceNames(decompiler, lowerKw);

			if (resourceFileNames.isEmpty()) {
				http.sendError(exchange, 404, "No resources found matching the criteria.");
				return;
			}

			Map<String, Object> result = PageUtil.paginate(
					resourceFileNames, offset, limit, "application-resources", "files", item -> item
			);

			http.sendResponse(exchange, 200, http.toJson(result));

		} catch (Exception e) {
			http.sendError(exchange, 500, e.getMessage());
		}
	}

	private List<String> extractResourceNames(JadxDecompiler decompiler, String lowerKw) {
		List<ResourceFile> resourceFiles = decompiler.getResources();
		List<String> fileNames = new ArrayList<>();

		for (ResourceFile resFile : resourceFiles) {
			try {
				String name = resFile.getDeobfName();
				if (name != null && !name.isBlank()) {
					if (lowerKw == null || name.toLowerCase().contains(lowerKw)) {
						fileNames.add(name);
					}
				}
			} catch (Exception ignored) {
			}
		}
		return fileNames;
	}
}
