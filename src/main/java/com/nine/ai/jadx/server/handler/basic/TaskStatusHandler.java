package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.TaskManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
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

			Map<String, Object> json = new LinkedHashMap<>();
			json.put("task_id", taskId);
			json.put("type", task.type);
			json.put("status", task.status);
			json.put("timestamp", task.timestamp);

			if ("SUCCESS".equals(task.status) && task.result != null) {
				// result is already a JSON string, wrap it as raw
				json.put("result", new com.google.gson.JsonParser().parse(task.result.toString()));
			} else if ("FAILED".equals(task.status)) {
				json.put("error", task.result != null ? task.result.toString() : "Unknown error");
			}

			http.sendResponse(exchange, 200, http.toJson(json));

		} catch (Exception e) {
			logger.error("Error checking task status", e);
			http.sendError(exchange, 500, "Internal server error");
		}
	}
}
