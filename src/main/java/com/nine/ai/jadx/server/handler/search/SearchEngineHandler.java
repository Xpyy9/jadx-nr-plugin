package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class SearchEngineHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(SearchEngineHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	// 引入原始的搜索 Handler
	private final HttpHandler methodSearchHandler = new MethodSearchHandler();
	private final HttpHandler classSearchHandler = new ClassSearchHandler();
	private final HttpHandler stringSearchHandler = new StringSearchHandler();
	private final HttpHandler cryptoScanHandler = new CryptoScanHandler();

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
				case "searchMethod":
					methodSearchHandler.handle(exchange);
					break;
				case "searchClass":
					classSearchHandler.handle(exchange);
					break;
				case "searchString":
					stringSearchHandler.handle(exchange);
					break;
				case "scanCrypto":
					cryptoScanHandler.handle(exchange);
					break;
				default:
					http.sendResponse(exchange, 400, "Invalid search action: " + action);
			}
		} catch (Exception e) {
			logger.error("Search Dispatcher Error", e);
			http.sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
		}
	}
}
