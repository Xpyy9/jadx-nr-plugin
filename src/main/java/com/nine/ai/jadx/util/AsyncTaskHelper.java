package com.nine.ai.jadx.util;

import com.nine.ai.jadx.server.PluginServer;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * 异步任务提交助手：封装 CompletableFuture + TaskManager + 异常处理的通用模式，
 * 消除各 Handler 中重复的 try-catch-202 样板代码。
 */
public final class AsyncTaskHelper {
	private static final Logger LOG = LoggerFactory.getLogger(AsyncTaskHelper.class);
	private static final HttpUtil http = HttpUtil.getInstance();

	private AsyncTaskHelper() {}

	/**
	 * 提交一个异步任务到 ASYNC_POOL，自动创建 task、捕获异常、更新状态。
	 *
	 * @param taskType 任务类型标识（如 STRING_SEARCH、CRYPTO_SCAN）
	 * @param exchange 当前 HTTP 交换（用于发送 202 响应）
	 * @param message  202 响应中的描述信息
	 * @param supplier 异步执行体，成功时返回 JSON 字符串结果
	 */
	public static void submit(String taskType, HttpExchange exchange, String message,
								Supplier<String> supplier) throws IOException {
		String taskId = TaskManager.createHighLoadTask(taskType);
		LOG.info("Async task {} [{}] started", taskType, taskId);

		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				String result = supplier.get();
				TaskManager.updateTask(taskId, "SUCCESS", result);
			} catch (Exception e) {
				LOG.error("Async task {} [{}] failed", taskType, taskId, e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = http.toJson(java.util.Map.of(
				"status", "ACCEPTED",
				"task_id", taskId,
				"message", message
		));
		http.sendResponse(exchange, 202, response);
	}
}
