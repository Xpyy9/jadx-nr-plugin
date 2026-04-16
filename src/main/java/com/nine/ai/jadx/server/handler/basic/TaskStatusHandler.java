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
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String taskId = params.get("task_id");

			if (taskId == null || taskId.isBlank()) {
				http.sendError(exchange, 400, "Missing task_id parameter");
				return;
			}

			TaskManager.TaskStatus task = TaskManager.getTask(taskId);
			if (task == null) {
				http.sendError(exchange, 404, "Task not found or expired");
				return;
			}

			StringBuilder json = new StringBuilder();
			json.append("{");
			json.append("\"task_id\":\"").append(taskId).append("\",");
			json.append("\"type\":\"").append(task.type).append("\",");
			json.append("\"status\":\"").append(task.status).append("\",");
			json.append("\"timestamp\":").append(task.timestamp);

			if ("SUCCESS".equals(task.status) && task.result != null) {
				json.append(",\"result\":").append(task.result);
			} else if ("FAILED".equals(task.status)) {
				json.append(",\"error\":\"").append(task.result != null ? HttpUtil.escapeJson(task.result.toString()) : "Unknown error").append("\"");
			}
			json.append("}");

			http.sendResponse(exchange, 200, json.toString());

		} catch (Exception e) {
			logger.error("Error checking task status", e);
			http.sendError(exchange, 500, "Internal server error");
		}
	}
}
