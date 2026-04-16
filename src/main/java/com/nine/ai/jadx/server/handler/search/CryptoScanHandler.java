package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.FingerprintUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.TaskManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class CryptoScanHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(CryptoScanHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String taskId = TaskManager.createHighLoadTask("CRYPTO_SCAN");
		logger.info("Started background crypto scan task: {}", taskId);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					logger.error("Crypto scan task {} failed: Decompiler unavailable", taskId);
					TaskManager.updateTask(taskId, "FAILED", "Decompiler unavailable");
					return;
				}

				List<Map<String, String>> suspects = FingerprintUtil.scanCryptoHinter(decompiler.getClassesWithInners());
				logger.info("Crypto scan task {} completed successfully. Found {} suspects.", taskId, suspects.size());

				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(suspects));
			} catch (Exception e) {
				logger.error("Async crypto scan task {} encountered a critical error", taskId, e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		});

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
