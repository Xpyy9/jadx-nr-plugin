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
	private static final Logger logger = LoggerFactory.getLogger(FieldRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public FieldRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = httpUtil.parseParams(exchange.getRequestURI().getQuery());
		String className = params.get("class_name");
		String fieldName = params.get("field_name");
		String newName = params.get("new_field_name");
		if (newName == null || newName.isBlank()) {
			newName = params.get("new_name");
		}

		if (className == null || className.isBlank()
				|| fieldName == null || fieldName.isBlank()
				|| newName == null || newName.isBlank()) {
			httpUtil.sendError(exchange, 400, "Missing required parameters: class_name, field_name, and new_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				httpUtil.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);

			if (cls == null) {
				httpUtil.sendError(exchange, 404, "Class " + className + " not found.");
				return;
			}

			JavaField targetField = CodeUtil.findField(cls, fieldName);

			if (targetField == null) {
				httpUtil.sendError(exchange, 404, "Field '" + fieldName + "' not found in class " + className);
				return;
			}

			ICodeNodeRef nodeRef = targetField.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetField.getName(), newName);
			event.setRenameNode(targetField.getFieldNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);
			CodeUtil.recordRename(newName, targetField.getName());

			try {
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming field, stale data may exist.", e);
			}

			logger.info("Renamed field {} in class {} to {}", fieldName, className, newName);
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed field",
                  "class_name": "%s",
                  "old_field_name": "%s",
                  "new_field_name": "%s"
                }
                """.formatted(HttpUtil.escapeJson(className), HttpUtil.escapeJson(fieldName), HttpUtil.escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename field error", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
