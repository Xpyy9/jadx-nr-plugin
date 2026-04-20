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
	private static final Logger logger = LoggerFactory.getLogger(ClassRenameHandler.class);
	private final MainWindow mainWindow;
	private final HttpUtil httpUtil = HttpUtil.getInstance();

	public ClassRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = httpUtil.parseParams(exchange.getRequestURI().getQuery());
		String className = params.get("class_name");
		String newName = params.get("new_name");

		if (className == null || className.isBlank() || newName == null || newName.isBlank()) {
			httpUtil.sendError(exchange, 400, "Missing required parameters: class_name and new_name");
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

			ICodeNodeRef nodeRef = cls.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
			event.setRenameNode(cls.getClassNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);
			CodeUtil.recordRename(newName, cls.getName());
			try {
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming, stale data may exist.", e);
			}

			logger.info("Renamed Class {} to {}", cls.getName(), newName);
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed class",
                  "old_name": "%s",
                  "new_name": "%s"
                }
                """.formatted(HttpUtil.escapeJson(cls.getName()), HttpUtil.escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Internal error while renaming class", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
