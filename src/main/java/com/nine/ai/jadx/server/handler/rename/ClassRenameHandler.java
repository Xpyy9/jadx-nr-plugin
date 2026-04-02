package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
import jadx.api.JadxDecompiler;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class ClassRenameHandler implements HttpHandler {
	/**
	 * 对 Android 应用程序中被混淆的类进行全局重命名（例如将无意义的 p001.a.b 重命名为 com.example.CryptoUtil）。
	 * 【行动指南】：
	 * 1. 当你通过阅读源码分析出某个混淆类的真实用途时，强烈建议立即调用此工具为其赋予一个有业务语义的名字。
	 * 2. 重命名是全局生效的，整个项目中所有调用该类的地方都会自动更新。这能极大降低你后续阅读和追踪代码的难度。
	 * 3. 【重要】：重命名成功后，原类名将失效。如果后续需要再次获取该类的代码或交叉引用，必须使用你新赋予的类名！
	 */
	private static final Logger logger = LoggerFactory.getLogger(ClassRenameHandler.class);
	private final MainWindow mainWindow;
	private final HttpUtil httpUtil = HttpUtil.getInstance();

	public ClassRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);

		String className = params.get("class_name");
		String newName = params.get("new_name");

		if (className == null || className.isBlank() || newName == null || newName.isBlank()) {
			sendError(exchange, 400, "Missing required parameters: class_name and new_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClass(cache, className);

			if (cls == null) {
				sendError(exchange, 404, "Class " + className + " not found.");
				return;
			}

			ICodeNodeRef nodeRef = cls.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
			event.setRenameNode(cls.getClassNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);
			CodeUtil.recordRename(newName, cls.getName()); // 记录重命名映射表
			try {
				CodeUtil.clearClassCache();
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming, stale data may exist.", e);
			}

			// 日志 + 标准化 JSON 返回
			logger.info("Renamed Class {} to {}", cls.getName(), newName);
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed class",
                  "old_name": "%s",
                  "new_name": "%s"
                }
                """.formatted(escapeJson(cls.getName()), escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Internal error while renaming class", e);
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
