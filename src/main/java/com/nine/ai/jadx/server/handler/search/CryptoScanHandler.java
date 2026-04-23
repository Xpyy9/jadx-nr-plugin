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

/**
 * 加密扫描 — CodeIndex 就绪时同步返回 200，否则走异步 202。
 */
public class CryptoScanHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(CryptoScanHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	/** 缓存扫描结果，APK 不变时结果一致 */
	private static volatile List<Map<String, String>> cachedResult = null;

	public static void clearScanCache() {
		cachedResult = null;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		// 缓存命中 → 同步返回
		List<Map<String, String>> cached = cachedResult;
		if (cached != null) {
			logger.info("Crypto scan cache hit");
			http.sendResponse(exchange, 200, http.toJson(cached));
			return;
		}

		// CodeIndex 已就绪 → 同步扫描
		CodeIndexManager indexManager = CodeIndexManager.getInstance();
		if (indexManager.isIndexed()) {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					http.sendError(exchange, 500, "Decompiler not available");
					return;
				}

				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<Map<String, String>> suspects = FingerprintUtil.scanCryptoFromIndex(codeIndex);

				cachedResult = suspects;
				logger.info("Crypto scan completed synchronously: {} suspects", suspects.size());
				http.sendResponse(exchange, 200, http.toJson(suspects));
			} catch (Exception e) {
				logger.error("Sync crypto scan failed", e);
				http.sendError(exchange, 500, "Crypto scan error: " + e.getMessage());
			}
			return;
		}

		// CodeIndex 未就绪 → 异步构建 + 扫描
		String taskId = TaskManager.createHighLoadTask("CRYPTO_SCAN");
		logger.info("CodeIndex not ready, started async crypto scan task: {}", taskId);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					TaskManager.updateTask(taskId, "FAILED", "Decompiler unavailable");
					return;
				}

				Map<String, String> codeIndex = indexManager.getIndex(decompiler);
				List<Map<String, String>> suspects = FingerprintUtil.scanCryptoFromIndex(codeIndex);

				cachedResult = suspects;
				logger.info("Crypto scan task {} completed: {} suspects", taskId, suspects.size());
				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(suspects));
			} catch (Exception e) {
				logger.error("Async crypto scan failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"CodeIndex building, scan started\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
