package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重命名 Handler 基类，提取参数校验、反编译器获取、类查找、缓存清理、JSON 响应的通用流程。
 * 子类只需实现 validateParams() 和 doRename()。
 */
public abstract class BaseRenameHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(BaseRenameHandler.class);
	protected final HttpUtil http = HttpUtil.getInstance();

	@Override
	public final void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());

		// 子类校验参数
		String error = validateParams(params);
		if (error != null) {
			http.sendError(exchange, 400, error);
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, Object> result = doRename(decompiler, params);

			// 缓存清理（重命名后必须刷新）
			try {
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming", e);
			}

			http.sendResponse(exchange, 200, http.toJson(result));
		} catch (RenameException e) {
			logger.warn("Rename business error in {}: {}", this.getClass().getSimpleName(), e.getMessage());
			http.sendError(exchange, e.getStatusCode(), e.getMessage());
		} catch (Exception e) {
			logger.error("Rename error in {}", this.getClass().getSimpleName(), e);
			http.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * 校验必要参数，返回 null 表示通过，否则返回错误信息。
	 */
	protected abstract String validateParams(Map<String, String> params);

	/**
	 * 执行重命名并返回结果 Map（将由 Gson 序列化为 JSON）。
	 */
	protected abstract Map<String, Object> doRename(JadxDecompiler decompiler, Map<String, String> params) throws Exception;

	// ===================== 通用辅助方法 =====================

	/**
	 * 查找类，找不到时抛出 RenameException(404)。
	 */
	protected JavaClass findClassOrThrow(JadxDecompiler decompiler, String className) throws Exception {
		Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
		JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);
		if (cls == null) throw RenameException.notFound("Class '" + className + "' not found.");
		return cls;
	}

	/**
	 * 构建成功响应 Map
	 */
	protected static Map<String, Object> successResult(String message, Map<String, Object> details) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("status", "success");
		result.put("message", message);
		if (details != null) result.putAll(details);
		return result;
	}
}
