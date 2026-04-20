package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class SystemStatusHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(SystemStatusHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		try {
			boolean isRunning = PluginServer.getInstance().isRunning();
			boolean decompilerReady = JadxUtil.getDecompiler() != null;

			Runtime runtime = Runtime.getRuntime();
			long maxMem = runtime.maxMemory() / 1024 / 1024;
			long totalMem = runtime.totalMemory() / 1024 / 1024;
			long freeMem = runtime.freeMemory() / 1024 / 1024;
			long usedMem = totalMem - freeMem;
			double usagePercent = (usedMem * 100.0) / maxMem;

			Map<String, Object> health = new LinkedHashMap<>();
			health.put("status", isRunning ? "UP" : "DOWN");
			health.put("decompiler_ready", decompilerReady);
			health.put("uptime_ms", System.currentTimeMillis() - PluginServer.getInstance().getStartTime());

			Map<String, Object> memory = new LinkedHashMap<>();
			memory.put("max_mb", maxMem);
			memory.put("used_mb", usedMem);
			memory.put("free_mb", freeMem);
			memory.put("usage_percent", String.format("%.2f%%", usagePercent));

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("health", health);
			result.put("resources", Map.of("memory", memory));
			result.put("timestamp", System.currentTimeMillis());

			int responseCode = (isRunning && decompilerReady) ? 200 : 503;
			http.sendResponse(exchange, responseCode, http.toJson(result));

		} catch (Exception e) {
			logger.error("System status check failed", e);
			http.sendError(exchange, 500, "Status check error: " + e.getMessage());
		}
	}
}
