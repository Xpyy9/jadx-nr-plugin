package com.nine.ai.jadx.server.handler;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * 路由分发基类。
 * 封装: 健康检查 → 层就绪验证 → 参数解析 → action分发 → 异常捕获。
 * 子类只需实现 dispatch() 和 requiredLayer()。
 */
public abstract class BaseHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BaseHandler.class);
    protected final HttpUtil http = HttpUtil.getInstance();
    protected final AnalysisLayers layers;

    protected BaseHandler(AnalysisLayers layers) {
        this.layers = layers;
    }

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
            String action = HttpUtil.sanitizeAction(params.get("action"));

            if (action == null || action.isBlank()) {
                http.sendError(exchange, 400, "Missing required parameter: 'action'");
                return;
            }

            // Check required layer readiness
            int required = requiredLayer(action);
            if (required >= 0 && !layers.isLayerChainReady(required)) {
                AnalysisLayers.LayerState state = layers.getState(required);
                if (state == AnalysisLayers.LayerState.NOT_STARTED || state == AnalysisLayers.LayerState.FAILED) {
                    http.sendError(exchange, 503, "Analysis layer " + required + " not ready (state=" + state + ")");
                } else {
                    // BUILDING → return 202 with progress
                    Map<String, Object> resp = Map.of(
                            "status", "building",
                            "layer", required,
                            "progress", layers.getProgress(required),
                            "message", "Index is being built. Retry in a few seconds."
                    );
                    http.sendResponse(exchange, 202, http.toJson(resp));
                }
                return;
            }

            dispatch(exchange, action, params);
        } catch (Exception e) {
            LOG.error("Handler error in {}: {}", this.getClass().getSimpleName(), e.getMessage(), e);
            http.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Return the minimum layer that must be READY for the given action.
     * Return -1 if no layer dependency (e.g., status check).
     */
    protected abstract int requiredLayer(String action);

    /**
     * Dispatch the action to the appropriate handler logic.
     */
    protected abstract void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException;
}
