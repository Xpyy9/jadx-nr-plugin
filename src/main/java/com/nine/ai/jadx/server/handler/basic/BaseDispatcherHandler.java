package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * 路由分发基类
 * 封装重复的健康检查、参数解析、异常捕获逻辑，减少各 Dispatcher 中的模板代码。
 * 子类只需实现 dispatch() 方法定义具体的 action -> handler 路由。
 */
public abstract class BaseDispatcherHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(BaseDispatcherHandler.class);
	protected final HttpUtil http = HttpUtil.getInstance();

	@Override
	public final void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendError(exchange, 503, "Service unavailable");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String action = HttpUtil.sanitizeAction(params.get("action"));

			if (action == null || action.isBlank()) {
				http.sendError(exchange, 400, "Missing required parameter: 'action'");
				return;
			}

			dispatch(exchange, action, params);
		} catch (Exception e) {
			logger.error("Dispatcher Error in {}", this.getClass().getSimpleName(), e);
			http.sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
		}
	}

	/**
	 * 子类实现具体的 Action 路由逻辑
	 */
	protected abstract void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException;
}
