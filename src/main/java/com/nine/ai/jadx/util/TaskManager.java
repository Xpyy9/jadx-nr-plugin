package com.nine.ai.jadx.util;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
	private static final Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();
	private static final long TASK_EXPIRE_MS = 30 * 60 * 1000; // 30 分钟过期
	private static final int MAX_TASKS = 200;

	public static String createHighLoadTask(String type) {
		cleanExpiredTasks();
		String taskId = UUID.randomUUID().toString().substring(0, 8);
		tasks.put(taskId, new TaskStatus(type, "RUNNING"));
		return taskId;
	}

	public static void updateTask(String taskId, String status, Object result) {
		TaskStatus task = tasks.get(taskId);
		if (task != null) {
			task.status = status;
			task.result = result;
			task.timestamp = System.currentTimeMillis();
		}
	}

	public static TaskStatus getTask(String taskId) {
		return tasks.get(taskId);
	}

	private static void cleanExpiredTasks() {
		long now = System.currentTimeMillis();
		tasks.entrySet().removeIf(e -> now - e.getValue().timestamp > TASK_EXPIRE_MS);

		if (tasks.size() > MAX_TASKS) {
			tasks.entrySet().stream()
					.sorted(Comparator.comparingLong(e -> e.getValue().timestamp))
					.limit(tasks.size() - MAX_TASKS)
					.forEach(e -> tasks.remove(e.getKey()));
		}
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
