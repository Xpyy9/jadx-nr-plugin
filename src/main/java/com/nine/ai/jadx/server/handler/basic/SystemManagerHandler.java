package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class SystemManagerHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(SystemManagerHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	// 引入原始的运维 Handler
	private final HttpHandler clearCacheHandler = new ClearCacheHandler();
	private final HttpHandler systemStatusHandler = new SystemStatusHandler();
	private final HttpHandler taskStatusHandler = new TaskStatusHandler();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String action = params.get("action");

			if (action == null || action.isBlank()) {
				http.sendResponse(exchange, 400, "Missing required parameter: 'action'");
				return;
			}

			switch (action) {
				case "systemStatus":
					systemStatusHandler.handle(exchange);
					break;
				case "clearCache":
					clearCacheHandler.handle(exchange);
					break;
				case "taskStatus":
					taskStatusHandler.handle(exchange);
					break;
				default:
					http.sendResponse(exchange, 400, "Invalid system action: " + action);
			}
		} catch (Exception e) {
			logger.error("System Dispatcher Error", e);
			http.sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
		}
	}
}
