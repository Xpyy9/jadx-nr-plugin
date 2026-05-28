package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.handler.basic.BaseDispatcherHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

public class SearchEngineHandler extends BaseDispatcherHandler {
	private final HttpHandler methodSearchHandler = new MethodSearchHandler();
	private final HttpHandler classSearchHandler = new ClassSearchHandler();
	private final HttpHandler stringSearchHandler = new StringSearchHandler();
	private final HttpHandler cryptoScanHandler = new CryptoScanHandler();

	@Override
	protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
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
			case "smartSearch":
				SmartSearchHandler.handle(exchange);
				break;
			default:
				http.sendError(exchange, 400, "Invalid search action: " + action);
		}
	}
}
