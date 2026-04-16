package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;

import java.io.IOException;
import java.util.Map;

public class SourceHandler implements HttpHandler {
	private final HttpUtil http = HttpUtil.getInstance();

	private static final int MAX_CONTENT_LENGTH = 200000;
	private static final int DEFAULT_MAX_LINE = 99999;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		if (!PluginServer.getInstance().isRunning()) {
			http.sendError(exchange, 503, "Service unavailable");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String fileName = params.get("file_name");
		String startLine = params.get("startLine");
		String endLine = params.get("endLine");

		if (fileName == null || fileName.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: file_name");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendError(exchange, 500, "Decompiler not available");
			return;
		}

		try {
			String content = getMatchedResourceContent(decompiler, fileName);
			if (content == null || content.isBlank()) {
				http.sendError(exchange, 404, "Resource not found: " + fileName);
				return;
			}
			if (content.length() > MAX_CONTENT_LENGTH) {
				content = content.substring(0, MAX_CONTENT_LENGTH) + "\n[TRUNCATED: Content too long. Please use startLine and endLine to read the rest.]";
			}
			int start = HttpUtil.parseInt(startLine, 1);
			int end = HttpUtil.parseInt(endLine, DEFAULT_MAX_LINE);
			start = Math.max(1, start);
			String resultContent = CodeUtil.extractLineRange(content, start, end);

			String json = """
                {
                  "type": "resource/text",
                  "file": {
                    "file_name": "%s",
                    "content": "%s"
                  }
                }
                """.formatted(HttpUtil.escapeJson(fileName), HttpUtil.escapeJson(resultContent));
			http.sendResponse(exchange, 200, json);

		} catch (Exception e) {
			http.sendError(exchange, 500, "Error loading resource: " + e.getMessage());
		}
	}

	private String getMatchedResourceContent(JadxDecompiler decompiler, String fileName) {
		Map<String, ResourceFile> cache = JadxUtil.getResourceCache(decompiler);
		if (cache == null) return null;

		ResourceFile target = cache.get(fileName);
		if (target != null) {
			return JadxUtil.getResourceContent(target);
		}
		ResourceFile arsc = cache.get("resources.arsc");
		if (arsc != null) {
			return JadxUtil.getArscResourceContent(arsc, fileName);
		}

		return null;
	}
}
