package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class SystemStatusHandler implements HttpHandler {
	/**
	 * 获取 JADX 逆向服务端的聚合状态报告。
	 * 【核心参数解析】：
	 * 1. health.status: 必须为 "UP" 才可执行后续任务。
	 * 2. health.decompiler_ready: 如果为 false，说明 APK 尚未加载成功或反编译引擎初始化失败。
	 * 3. resources.memory: 实时监控内存占用。如果在执行“全局搜索”前发现 usage_percent > 85%，建议先调用 clear_cache 工具释放内存。
	 * 【使用建议】：作为你接手任务后的第一个动作，或者在遇到服务端响应缓慢时的自查动作。
	 */
	private static final Logger logger = LoggerFactory.getLogger(SystemStatusHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "{\"error\":\"Only GET allowed\"}");
			return;
		}

		try {
			// 1. 获取基础运行状态
			boolean isRunning = PluginServer.getInstance().isRunning();
			boolean decompilerReady = JadxUtil.getDecompiler() != null;

			// 2. 获取 JVM 内存画像 (MB)
			Runtime runtime = Runtime.getRuntime();
			long maxMem = runtime.maxMemory() / 1024 / 1024;
			long totalMem = runtime.totalMemory() / 1024 / 1024;
			long freeMem = runtime.freeMemory() / 1024 / 1024;
			long usedMem = totalMem - freeMem;
			double usagePercent = (usedMem * 100.0) / maxMem;

			// 3. 构建聚合 JSON
			// 包含健康状态(health)、服务配置(status)和资源占用(resource)
			String json = """
                {
                  "health": {
                    "status": "%s",
                    "decompiler_ready": %b,
                    "uptime_ms": %d
                  },
                  "config": {
                    "port": 13997,
                    "cors_enabled": %b,
                    "version": "1.0.0-Agent-Core"
                  },
                  "resources": {
                    "memory": {
                        "max_mb": %d,
                        "used_mb": %d,
                        "free_mb": %d,
                        "usage_percent": "%.2f%%"
                    }
                  },
                  "timestamp": %d
                }
                """.formatted(
					isRunning ? "UP" : "DOWN",
					decompilerReady,
					System.currentTimeMillis() - PluginServer.getInstance().getStartTime(), // 假设你在 Server 里记录了启动时间
					PluginServer.getInstance().isCorsEnabled(),
					maxMem, usedMem, freeMem, usagePercent,
					System.currentTimeMillis()
			);

			// 根据健康状态返回响应码：200 正常，503 服务未就绪
			int responseCode = (isRunning && decompilerReady) ? 200 : 503;
			http.sendResponse(exchange, responseCode, json);

		} catch (Exception e) {
			logger.error("System status check failed", e);
			http.sendResponse(exchange, 500, "{\"status\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}");
		}
	}
}
