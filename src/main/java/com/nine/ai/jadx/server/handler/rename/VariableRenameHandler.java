package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JadxDecompiler;
import jadx.api.metadata.annotations.VarNode;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class VariableRenameHandler implements HttpHandler {
	/**
	 * 对 Android 应用程序中某个具体方法内部的“局部变量”（如 for 循环里的 i，或自动生成的 str1, obj2）进行重命名。
	 * 【行动指南】：
	 * 当你深入阅读某个核心方法的源码（如加密算法实现）时，如果遇到毫无意义的局部变量名，通过上下文推断出它的真实含义后，请立即使用此工具重命名它（例如将 byte[] b 改为 byte[] cipherText）。这能极大增强你对该函数逻辑的记忆和理解。
	 * 【注意】：此工具仅适用于方法内部的局部变量。如果需要重命名类的成员变量（Field），请使用 rename_field。如果因为方法重载导致修改失败，请在 method_name 中附带完整的方法签名进行精确定位。
	 */
	private static final Logger logger = LoggerFactory.getLogger(VariableRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public VariableRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getQuery();
		Map<String, String> params = httpUtil.parseParams(query);

		String className = params.get("class_name");
		String rawMethodName = params.get("method_name");
		String variableName = params.get("variable_name");
		String newName = params.get("new_name");

		// 可选参数：精确匹配寄存器 / SSA 版本
		String regStr = params.get("reg");
		String ssaStr = params.get("ssa");

		if (className == null || className.isBlank()
				|| rawMethodName == null || rawMethodName.isBlank()
				|| variableName == null || variableName.isBlank()
				|| newName == null || newName.isBlank()) {
			sendError(exchange, 400, "Missing required parameters: class_name, method_name, variable_name, new_name");
			return;
		}

		// 1. 智能拆分方法签名，支持精确重载匹配
		String methodName = rawMethodName;
		String signature = null;
		if (methodName.contains("(")) {
			int sigIdx = methodName.indexOf('(');
			signature = methodName.substring(sigIdx);
			methodName = methodName.substring(0, sigIdx);
		}

		// 兼容某些大模型可能错误地传入了完整包名路径
		int lastDotIdx = methodName.lastIndexOf('.');
		if (lastDotIdx != -1) {
			methodName = methodName.substring(lastDotIdx + 1);
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

			// ================= 核心优化 2：精确匹配目标方法 =================
			JavaMethod targetMethod = null;
			for (JavaMethod method : cls.getMethods()) {
				if (method.getName().equals(methodName)) {
					if (signature != null) {
						try {
							if (!method.getMethodNode().getMethodInfo().getShortId().endsWith(signature)) {
								continue;
							}
						} catch (Exception ignored) {}
					}
					targetMethod = method;
					break;
				}
			}

			if (targetMethod == null) {
				sendError(exchange, 404, "Method '" + rawMethodName + "' not found in class " + className);
				return;
			}

			// ================= 核心逻辑：SSA 变量树解析与重命名 =================
			MethodNode methodNode = targetMethod.getMethodNode();
			if (methodNode == null) {
				sendError(exchange, 500, "Failed to get MethodNode for " + methodName);
				return;
			}

			List<SSAVar> sVars = methodNode.getSVars();
			// SSA 变量为空 → 强制重新加载类（保留了你原版的硬核逻辑）
			if (sVars == null || sVars.isEmpty()) {
				logger.info("SSA variables empty for method {}, forcing reload...", targetMethod.getName());
				try {
					cls.getClassNode().unload();
					cls.getClassNode().root().getProcessClasses().forceProcess(cls.getClassNode());
					MethodNode newMethodNode = cls.getClassNode().searchMethodByShortName(targetMethod.getName());
					if (newMethodNode != null) {
						methodNode = newMethodNode;
						sVars = methodNode.getSVars();
					}
				} catch (Exception e) {
					logger.error("Force process class failed", e);
				}
			}

			if (sVars == null || sVars.isEmpty()) {
				sendError(exchange, 404, "No local variables found in method " + methodName + ". Code might be empty or not fully decompiled.");
				return;
			}

			boolean renamed = false;
			for (SSAVar sVar : sVars) {
				boolean nameMatch = variableName.equals(sVar.getName());
				boolean regMatch = (regStr == null || regStr.isBlank()) || String.valueOf(sVar.getRegNum()).equals(regStr);
				boolean ssaMatch = (ssaStr == null || ssaStr.isBlank()) || String.valueOf(sVar.getVersion()).equals(ssaStr);

				if (nameMatch && regMatch && ssaMatch) {
					VarNode varNode = VarNode.get(methodNode, sVar);
					if (varNode != null) {
						NodeRenamedByUser event = new NodeRenamedByUser(varNode, variableName, newName);
						event.setRenameNode(varNode);
						event.setResetName(newName.isEmpty());
						mainWindow.events().send(event);
						renamed = true;
						break;
					}
				}
			}

			if (!renamed) {
				sendError(exchange, 404, "Variable '" + variableName + "' not found in method " + rawMethodName + ". Tip: Check if the variable exists in the source code.");
				return;
			}

			// ================= 核心优化 3：强制使旧缓存失效 =================
			try {
				CodeUtil.clearClassCache();
				JadxUtil.clearCaches();
			} catch (Exception e) {
				logger.warn("Failed to clear caches after renaming variable.", e);
			}

			logger.info("Renamed variable {} to {} in {}", variableName, newName, targetMethod.getName());
			String resultJson = """
                {
                  "status": "success",
                  "message": "Successfully renamed local variable",
                  "class_name": "%s",
                  "method_name": "%s",
                  "old_variable_name": "%s",
                  "new_variable_name": "%s"
                }
                """.formatted(escapeJson(className), escapeJson(rawMethodName), escapeJson(variableName), escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename variable error", e);
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
