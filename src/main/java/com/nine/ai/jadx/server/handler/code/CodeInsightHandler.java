package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class CodeInsightHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(CodeInsightHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private final HttpHandler classHandler = new ClassHandler();
	private final HttpHandler allClassHandler = new AllClassHandler();
	private final HttpHandler structureHandler = new ClassStructureHandler();
	private final HttpHandler smaliHandler = new SmaliHandler();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		try {
			// 仅解析参数用于获取 action
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String action = params.get("action");

			if (action == null || action.isBlank()) {
				http.sendResponse(exchange, 400, "Missing required parameter: 'action'");
				return;
			}

			// 根据 action，将 exchange 原封不动地传递给旧的 Handler
			switch (action) {
				case "getAllClasses":
					allClassHandler.handle(exchange);
					break;
				case "getClassCode":
					classHandler.handle(exchange);
					break;
				case "getClassStructure":
					structureHandler.handle(exchange);
					break;
				case "getClassSmali":
					smaliHandler.handle(exchange);
					break;
				default:
					http.sendResponse(exchange, 400, "Invalid action: " + action);
			}
		} catch (Exception e) {
			logger.error("Dispatcher failed routing action", e);
			http.sendResponse(exchange, 500, "Dispatcher Error: " + e.getMessage());
		}
	}
}
