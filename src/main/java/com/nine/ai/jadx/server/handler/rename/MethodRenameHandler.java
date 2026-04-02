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
	/**
	 * 对 Android 应用程序中的混淆方法进行重命名。这是梳理调用链（执行流）的绝佳方式！当你分析出 a.b() 实际上是在进行“解密”操作时，立即将其重命名为 decrypt。
	 * 重命名后，全项目中所有调用该方法的地方都会自动更新为你赋予的新名字，大大降低代码阅读的复杂度。
	 * 【高级技巧】：由于代码中可能存在同名重载方法（例如两个同名的 a() 方法），如果工具没有修改到你想改的那个，请在 method_name 中附带方法签名进行精确打击。
	 */
	private static final Logger logger = LoggerFactory.getLogger(MethodRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public MethodRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);

		String rawMethodName = params.get("method_name");
		String newName = params.get("new_name");

		if (rawMethodName == null || rawMethodName.isBlank() || newName == null || newName.isBlank()) {
			sendError(exchange, 400, "Missing required parameters: method_name and new_name");
			return;
		}

		// 1. 智能拆分类名、方法名和可能存在的方法签名
		String fullMethodPath = rawMethodName;
		String signature = null;

		if (fullMethodPath.contains("(")) {
			int sigIdx = fullMethodPath.indexOf('(');
			signature = fullMethodPath.substring(sigIdx); // 保留签名如 (Ljava/lang/String;)V 以便精确匹配重载
			fullMethodPath = fullMethodPath.substring(0, sigIdx);
		}

		int lastDotIdx = fullMethodPath.lastIndexOf('.');
		if (lastDotIdx == -1) {
			sendError(exchange, 400, "Invalid method_name format. Expected: com.example.MyClass.myMethod");
			return;
		}

		String className = fullMethodPath.substring(0, lastDotIdx);
		String methodName = fullMethodPath.substring(lastDotIdx + 1);

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			// ================= 核心优化 1：极速 O(1) 定位类，消灭双重遍历 =================
			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClass(cache, className);

			if (cls == null) {
				sendError(exchange, 404, "Class " + className + " not found.");
				return;
			}

			// ================= 核心优化 2：支持精确匹配重载方法 =================
			JavaMethod targetMethod = null;
			for (JavaMethod method : cls.getMethods()) {
				if (method.getName().equals(methodName)) {
					// 如果传入了签名，必须签名也匹配（解决重载问题）
					if (signature != null) {
						try {
							if (!method.getMethodNode().getMethodInfo().getShortId().endsWith(signature)) {
								continue;
							}
						} catch (Exception ignored) {}
					}
					targetMethod = method;
					break; // 找到即止
				}
			}

			if (targetMethod == null) {
				sendError(exchange, 404, "Method '" + methodName + "' not found in class " + className);
				return;
			}

			// ================= 核心逻辑：触发重命名事件 =================
			ICodeNodeRef nodeRef = targetMethod.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetMethod.getName(), newName);
			event.setRenameNode(targetMethod.getMethodNode());
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);

			// ================= 核心优化 3：强制使旧缓存失效 =================
			try {
				CodeUtil.clearClassCache();
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
                """.formatted(escapeJson(className), escapeJson(methodName), escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename method error", e);
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
