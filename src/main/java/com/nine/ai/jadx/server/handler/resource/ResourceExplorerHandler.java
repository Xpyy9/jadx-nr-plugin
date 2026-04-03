package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class ResourceExplorerHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ResourceExplorerHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	// 引入原始的、验证过的单个 Handler
	private final HttpHandler mainActivityHandler = new MainActivityHandler();
	private final HttpHandler mainApplicationHandler = new MainApplicationHandler();
	private final HttpHandler allResourceHandler = new AllResourceFileNameHandler();
	private final HttpHandler resourceFileHandler = new SourceHandler();

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
				case "getMainActivity":
					mainActivityHandler.handle(exchange);
					break;
				case "getMainAppClasses":
					mainApplicationHandler.handle(exchange);
					break;
				case "getAllResourceNames":
					allResourceHandler.handle(exchange);
					break;
				case "getResourceFile":
					resourceFileHandler.handle(exchange);
					break;
				default:
					http.sendResponse(exchange, 400, "Invalid resource action: " + action);
			}
		} catch (Exception e) {
			logger.error("Resource Dispatcher Error", e);
			http.sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
		}
	}
}
