package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CryptoScanHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(CryptoScanHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	/** 缓存扫描结果 JSON，APK 不变时结果一致 */
	private static volatile String cachedResult = null;

	public static void clearScanCache() {
		cachedResult = null;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String taskId = TaskManager.createHighLoadTask("CRYPTO_SCAN");

		// 命中缓存：立即返回
		String cached = cachedResult;
		if (cached != null) {
			logger.info("Crypto scan cache hit, task: {}", taskId);
			TaskManager.updateTask(taskId, "SUCCESS", cached);
			String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Result from cache\"}", taskId);
			http.sendResponse(exchange, 202, response);
			return;
		}

		logger.info("Started background crypto scan task: {}", taskId);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					logger.error("Crypto scan task {} failed: Decompiler unavailable", taskId);
					TaskManager.updateTask(taskId, "FAILED", "Decompiler unavailable");
					return;
				}

				// 使用全局代码索引，避免重复反编译
				Map<String, String> codeIndex = CodeIndexManager.getInstance().getIndex(decompiler);
				List<Map<String, String>> suspects = FingerprintUtil.scanCryptoFromIndex(codeIndex);

				logger.info("Crypto scan task {} completed. Found {} suspects.", taskId, suspects.size());

				String resultJson = http.toJson(suspects);
				cachedResult = resultJson;
				TaskManager.updateTask(taskId, "SUCCESS", resultJson);
			} catch (Exception e) {
				logger.error("Async crypto scan task {} encountered a critical error", taskId, e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
