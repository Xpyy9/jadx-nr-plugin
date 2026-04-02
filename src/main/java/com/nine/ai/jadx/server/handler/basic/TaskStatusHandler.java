package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.TaskManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class TaskStatusHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(TaskStatusHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "{\"error\":\"Only GET allowed\"}");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String taskId = params.get("task_id");

			if (taskId == null || taskId.isBlank()) {
				http.sendResponse(exchange, 400, "{\"error\":\"Missing task_id parameter\"}");
				return;
			}

			TaskManager.TaskStatus task = TaskManager.getTask(taskId);
			if (task == null) {
				http.sendResponse(exchange, 404, "{\"error\":\"Task not found or expired\"}");
				return;
			}

			// 返回任务当前快照
			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"task_id\":\"").append(taskId).append("\",");
			json.append("\"type\":\"").append(task.type).append("\",");
			json.append("\"status\":\"").append(task.status).append("\",");
			json.append("\"timestamp\":").append(task.timestamp);

			if ("SUCCESS".equals(task.status) && task.result != null) {
				// 假设结果已经是格式化好的 JSON 字符串
				json.append(",\"result\":").append(task.result);
			} else if ("FAILED".equals(task.status)) {
				json.append(",\"error\":\"").append(task.result != null ? escapeJson(task.result.toString()) : "Unknown error").append("\"");
			}
			json.append("}");

			http.sendResponse(exchange, 200, json.toString());

		} catch (Exception e) {
			logger.error("Error checking task status", e);
			http.sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
		}
	}

	private String escapeJson(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
