package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JadxDecompiler;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class MethodRenameHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(MethodRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public MethodRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = httpUtil.parseParams(exchange.getRequestURI().getQuery());
		String rawMethodName = params.get("method_name");
		String classNameParam = params.get("class_name");
		String newName = params.get("new_name");

		if (rawMethodName == null || rawMethodName.isBlank() || newName == null || newName.isBlank()) {
			httpUtil.sendError(exchange, 400, "Missing required parameters: method_name and new_name");
			return;
		}

		String fullMethodPath = rawMethodName;
		String signature = null;

		if (fullMethodPath.contains("(")) {
			int sigIdx = fullMethodPath.indexOf('(');
			signature = fullMethodPath.substring(sigIdx);
			fullMethodPath = fullMethodPath.substring(0, sigIdx);
		}

		String className;
		String methodName;

		int lastDotIdx = fullMethodPath.lastIndexOf('.');
		if (lastDotIdx != -1) {
			// method_name 包含完整路径: com.example.MyClass.myMethod
			className = fullMethodPath.substring(0, lastDotIdx);
			methodName = fullMethodPath.substring(lastDotIdx + 1);
		} else if (classNameParam != null && !classNameParam.isBlank()) {
			// method_name 只有短方法名，从 class_name 参数取类名
			className = classNameParam;
			methodName = fullMethodPath;
		} else {
			httpUtil.sendError(exchange, 400,
					"Cannot determine class. Either use full path in method_name (com.example.MyClass.myMethod) or provide class_name parameter.");
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

			JavaMethod targetMethod = CodeUtil.findMethod(cls, methodName + (signature != null ? signature : ""));

			if (targetMethod == null) {
				httpUtil.sendError(exchange, 404, "Method '" + methodName + "' not found in class " + className);
				return;
			}

			ICodeNodeRef nodeRef = targetMethod.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetMethod.getName(), newName);
			event.setRenameNode(targetMethod.getMethodNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);
			CodeUtil.recordRename(newName, targetMethod.getName());

			try {
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming method, stale data may exist.", e);
			}

			logger.info("Renamed method {} in class {} to {}", methodName, className, newName);
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed method",
                  "class_name": "%s",
                  "old_method_name": "%s",
                  "new_method_name": "%s"
                }
                """.formatted(HttpUtil.escapeJson(className), HttpUtil.escapeJson(methodName), HttpUtil.escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename method error", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
