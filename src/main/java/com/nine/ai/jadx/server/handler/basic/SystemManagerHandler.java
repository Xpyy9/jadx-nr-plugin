package com.nine.ai.jadx.server.handler.basic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

public class SystemManagerHandler extends BaseDispatcherHandler {
	private final HttpHandler clearCacheHandler = new ClearCacheHandler();
	private final HttpHandler systemStatusHandler = new SystemStatusHandler();
	private final HttpHandler taskStatusHandler = new TaskStatusHandler();
	private final ApkOverviewHandler apkOverviewHandler = new ApkOverviewHandler();

	public ApkOverviewHandler getApkOverviewHandler() {
		return apkOverviewHandler;
	}

	@Override
	protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
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
			case "getApkOverview":
				apkOverviewHandler.handle(exchange);
				break;
			default:
				http.sendError(exchange, 400, "Invalid system action: " + action);
		}
	}
}
