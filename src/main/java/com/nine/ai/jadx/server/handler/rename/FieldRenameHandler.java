package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JadxDecompiler;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class FieldRenameHandler implements HttpHandler {
	/**
	 * 对 Android 应用程序中某个类内部的混淆字段（成员变量）进行重命名。例如将 this.a 重命名为 this.encryptionKey。
	 * 【行动指南】：
	 * 1. 这是理清数据流向的绝佳工具。当你分析出某个毫无意义的变量名其实代表了“密码”、“URL”或“时间戳”时，立刻调用它！
	 * 2. 重命名后，整个应用中所有读取或写入该字段的地方都会自动同步更新为新名字。
	 * 3. 【重要】：该工具仅能重命名类的成员变量（Field）。如果你想重命名方法内部的局部变量（如 for 循环里的 i），请使用 rename_variable 工具。
	 */
	private static final Logger logger = LoggerFactory.getLogger(FieldRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public FieldRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);

		String className = params.get("class_name");
		String fieldName = params.get("field_name");
		// 兼容 old API new_field_name 和标准化的 new_name
		String newName = params.get("new_field_name");
		if (newName == null || newName.isBlank()) {
			newName = params.get("new_name");
		}

		if (className == null || className.isBlank()
				|| fieldName == null || fieldName.isBlank()
				|| newName == null || newName.isBlank()) {
			sendError(exchange, 400, "Missing required parameters: class_name, field_name, and new_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			// ================= 核心优化 1：极速 O(1) 查找类 =================
			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClass(cache, className);

			if (cls == null) {
				sendError(exchange, 404, "Class " + className + " not found.");
				return;
			}

			// 遍历查找指定字段
			JavaField targetField = null;
			for (JavaField field : cls.getFields()) {
				if (field.getName().equals(fieldName)) {
					targetField = field;
					break;
				}
			}

			if (targetField == null) {
				sendError(exchange, 404, "Field '" + fieldName + "' not found in class " + className);
				return;
			}

			// ================= 核心逻辑：触发 JADX 重命名事件 =================
			ICodeNodeRef nodeRef = targetField.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetField.getName(), newName);
			event.setRenameNode(targetField.getFieldNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);

			// ================= 核心优化 2：强制使旧缓存失效 =================
			try {
				CodeUtil.clearClassCache();
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming field, stale data may exist.", e);
			}

			// 日志 + 标准化 JSON 返回
			logger.info("Renamed field {} in class {} to {}", fieldName, className, newName);
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed field",
                  "class_name": "%s",
                  "old_field_name": "%s",
                  "new_field_name": "%s"
                }
                """.formatted(escapeJson(className), escapeJson(fieldName), escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename field error", e);
			sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
		String json = """
                {
                  "error": "%s"
                }
                """.formatted(escapeJson(msg));
		httpUtil.sendResponse(exchange, code, json);
	}

	private String escapeJson(String s) {
		return s == null ? "" : s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}
}
