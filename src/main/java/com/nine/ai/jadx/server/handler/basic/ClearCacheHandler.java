package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ClearCacheHandler implements HttpHandler {
	/**
	 * 手动强制清空服务端的反编译代码缓存和资源缓存。
	 * 【使用场景】：
	 * 1. 如果你在进行了大量的 rename 重命名操作后，发现获取到的代码依然是旧代码。
	 * 2. 如果你怀疑服务端出现状态同步错误或频繁报错。
	 * 你可以调用此工具重置服务端状态。清理后，下一次查询类代码会重新触发底层的反编译，可能会稍微耗时。
	 */
	private static final Logger logger = LoggerFactory.getLogger(ClearCacheHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
			sendError(exchange, 405, "Only GET or POST allowed");
			return;
		}
		try {
			try {
				CodeUtil.clearClassCache();
			} catch (Exception e) {
				logger.warn("Failed to clear CodeUtil cache", e);
			}

			try {
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear JadxUtil cache", e);
			}
			logger.info("Manual cache clear triggered via API.");
			String json = """
                   {
                     "status": "success",
                     "message": "All caches (code and resources) cleared successfully."
                   }
                   """;
			http.sendResponse(exchange, 200, json);

		} catch (Exception e) {
			logger.error("Error clearing cache", e);
			sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
		String json = """
                {
                  "error": "%s"
                }
                """.formatted(escapeJson(msg));
		http.sendResponse(exchange, code, json);
	}

	private String escapeJson(String s) {
		return s == null ? "" : s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}
}
