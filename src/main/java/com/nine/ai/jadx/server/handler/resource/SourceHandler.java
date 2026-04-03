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
	/**
	 * 获取指定的 Android 资源文件或配置文件的纯文本内容。
	 * 你可以用它来读取 AndroidManifest.xml（查看组件和权限）、strings.xml（查看硬编码字符串）或 assets/ 目录下的任何配置文件。
	 * 【重要护栏】：对于超过几千行的大型 XML 或 JSON 文件，直接拉取全部内容会导致你的上下文超载甚至丢失关键信息。当文件过大时，请务必利用 startLine 和 endLine 参数进行分块阅读。
	 */

	private final HttpUtil http = HttpUtil.getInstance();

	// 安全限制：200,000 字符大约 4-5 万 Token
	private static final int MAX_CONTENT_LENGTH = 200000;
	private static final int DEFAULT_MAX_LINE = 99999;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String fileName = params.get("file_name");
		String startLine = params.get("startLine");
		String endLine = params.get("endLine");

		if (fileName == null || fileName.isBlank()) {
			sendErrorJson(exchange, 400, "Missing required parameter: file_name");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			sendErrorJson(exchange, 500, "Decompiler not available");
			return;
		}

		try {
			String content = getMatchedResourceContent(decompiler, fileName);
			if (content == null || content.isBlank()) {
				sendErrorJson(exchange, 404, "Resource not found: " + fileName);
				return;
			}
			if (content.length() > MAX_CONTENT_LENGTH) {
				content = content.substring(0, MAX_CONTENT_LENGTH) + "\n[TRUNCATED: Content too long. Please use startLine and endLine to read the rest.]";
			}
			int start = parseLine(startLine, 1);
			int end = parseLine(endLine, DEFAULT_MAX_LINE);
			String resultContent = CodeUtil.extractLineRange(content, start, end);
			String json = buildSuccessJson(fileName, resultContent);
			http.sendResponse(exchange, 200, json);

		} catch (Exception e) {
			sendErrorJson(exchange, 500, "Error loading resource: " + e.getMessage());
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

	private int parseLine(String lineStr, int defaultValue) {
		if (lineStr == null || lineStr.isBlank()) return defaultValue;
		try {
			int num = Integer.parseInt(lineStr.trim());
			return Math.max(1, num);
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private String buildSuccessJson(String fileName, String content) {
		return """
                {
                  "type": "resource/text",
                  "file": {
                    "file_name": "%s",
                    "content": "%s"
                  }
                }
                """.formatted(
				escapeJson(fileName),
				escapeJson(content)
		);
	}

	private void sendErrorJson(HttpExchange exchange, int code, String msg) throws IOException {
		String json = """
                {
                  "code": %d,
                  "error": "%s"
                }
                """.formatted(code, escapeJson(msg));
		http.sendResponse(exchange, code, json);
	}

	// 核心修复：增加对制表符 (\t) 和退格等隐藏字符的安全转义，防止大模型 JSON 解析器崩溃
	private String escapeJson(String s) {
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}
}
