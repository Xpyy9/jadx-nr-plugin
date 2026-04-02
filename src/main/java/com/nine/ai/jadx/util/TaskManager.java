package com.nine.ai.jadx.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
	// 任务管理器
	private static final Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();

	public static String createHighLoadTask(String type) {
		String taskId = UUID.randomUUID().toString().substring(0, 8);
		tasks.put(taskId, new TaskStatus(type, "RUNNING"));
		return taskId;
	}

	public static void updateTask(String taskId, String status, Object result) {
		TaskStatus task = tasks.get(taskId);
		if (task != null) {
			task.status = status;
			task.result = result;
		}
	}

	public static TaskStatus getTask(String taskId) {
		return tasks.get(taskId);
	}

	public static class TaskStatus {
		public String type;
		public String status;
		public Object result;
		public long timestamp = System.currentTimeMillis();

		public TaskStatus(String type, String status) {
			this.type = type;
			this.status = status;
		}
	}
}
