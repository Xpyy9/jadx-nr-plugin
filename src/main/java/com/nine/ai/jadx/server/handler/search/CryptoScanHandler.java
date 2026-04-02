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

				// 执行耗时的全量指纹扫描
				List<Map<String, String>> suspects = FingerprintUtil.scanCryptoHinter(decompiler.getClassesWithInners());
				logger.info("Crypto scan task {} completed successfully. Found {} suspects.", taskId, suspects.size());

				// 转换为 JSON 结果
				StringBuilder sb = new StringBuilder("[");
				for (int i = 0; i < suspects.size(); i++) {
					Map<String, String> item = suspects.get(i);
					sb.append(String.format("{\"class\":\"%s\", \"hint\":\"%s\"}",
							item.get("class"), item.get("hint")));
					if (i < suspects.size() - 1) sb.append(",");
				}
				sb.append("]");

				TaskManager.updateTask(taskId, "SUCCESS", sb.toString());
			} catch (Exception e) {
				logger.error("Async crypto scan task {} encountered a critical error", taskId, e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		});

		String response = String.format("{\"status\":\"ACCEPTED\", \"task_id\":\"%s\"}", taskId);
		http.sendResponse(exchange, 202, response);
	}
}
