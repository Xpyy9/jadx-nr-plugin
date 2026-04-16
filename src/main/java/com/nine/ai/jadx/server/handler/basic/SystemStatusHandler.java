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
	/**
	 * 获取 JADX 逆向服务端的聚合状态报告。
	 * 【核心参数解析】：
	 * 1. health.status: 必须为 "UP" 才可执行后续任务。
	 * 2. health.decompiler_ready: 如果为 false，说明 APK 尚未加载成功或反编译引擎初始化失败。
	 * 3. resources.memory: 实时监控内存占用。如果在执行"全局搜索"前发现 usage_percent > 85%，建议先调用 clear_cache 工具释放内存。
	 * 【使用建议】：作为你接手任务后的第一个动作，或者在遇到服务端响应缓慢时的自查动作。
	 */
	private static final Logger logger = LoggerFactory.getLogger(SystemStatusHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private static final List<Map<String, Object>> AVAILABLE_APIS = buildApiDirectory();

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

			Map<String, Object> config = new LinkedHashMap<>();
			config.put("port", 13997);
			config.put("cors_enabled", PluginServer.getInstance().isCorsEnabled());
			config.put("version", "0.1.9-Agent-Core");

			Map<String, Object> memory = new LinkedHashMap<>();
			memory.put("max_mb", maxMem);
			memory.put("used_mb", usedMem);
			memory.put("free_mb", freeMem);
			memory.put("usage_percent", String.format("%.2f%%", usagePercent));

			Map<String, Object> resources = new LinkedHashMap<>();
			resources.put("memory", memory);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("health", health);
			result.put("config", config);
			result.put("resources", resources);
			result.put("available_apis", AVAILABLE_APIS);
			result.put("timestamp", System.currentTimeMillis());

			int responseCode = (isRunning && decompilerReady) ? 200 : 503;
			http.sendResponse(exchange, responseCode, http.toJson(result));

		} catch (Exception e) {
			logger.error("System status check failed", e);
			http.sendError(exchange, 500, "Status check error: " + e.getMessage());
		}
	}

	private static List<Map<String, Object>> buildApiDirectory() {
		List<Map<String, Object>> apis = new ArrayList<>();

		apis.add(apiEntry("/codeInsight", List.of("getAllClasses", "getClassCode", "getClassStructure", "getClassSmali")));
		apis.add(apiEntry("/resourceExplorer", List.of("getMainActivity", "getMainAppClasses", "getAllResourceNames", "getResourceFile")));
		apis.add(apiEntry("/searchEngine", List.of("searchMethod", "searchClass", "searchString", "scanCrypto")));
		apis.add(Map.of("path", "/getXrefs", "params", List.of("class", "method?", "field?", "offset?", "limit?")));
		apis.add(apiEntry("/refactor", List.of("renameClass", "renameMethod", "renameField", "renameVariable", "exportMapping")));
		apis.add(apiEntry("/systemManager", List.of("systemStatus", "clearCache", "taskStatus", "getApkOverview")));

		return Collections.unmodifiableList(apis);
	}

	private static Map<String, Object> apiEntry(String path, List<String> actions) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("path", path);
		entry.put("actions", actions);
		return entry;
	}
}
