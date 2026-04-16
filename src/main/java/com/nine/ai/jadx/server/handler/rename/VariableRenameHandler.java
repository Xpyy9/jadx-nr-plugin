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
	private static final Logger logger = LoggerFactory.getLogger(VariableRenameHandler.class);
	private final HttpUtil httpUtil = HttpUtil.getInstance();
	private final MainWindow mainWindow;

	public VariableRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> params = httpUtil.parseParams(exchange.getRequestURI().getQuery());
		String className = params.get("class_name");
		String rawMethodName = params.get("method_name");
		String variableName = params.get("variable_name");
		String newName = params.get("new_name");
		String regStr = params.get("reg");
		String ssaStr = params.get("ssa");

		if (className == null || className.isBlank()
				|| rawMethodName == null || rawMethodName.isBlank()
				|| variableName == null || variableName.isBlank()
				|| newName == null || newName.isBlank()) {
			httpUtil.sendError(exchange, 400, "Missing required parameters: class_name, method_name, variable_name, new_name");
			return;
		}

		String methodName = rawMethodName;
		String signature = null;
		if (methodName.contains("(")) {
			int sigIdx = methodName.indexOf('(');
			signature = methodName.substring(sigIdx);
			methodName = methodName.substring(0, sigIdx);
		}

		int lastDotIdx = methodName.lastIndexOf('.');
		if (lastDotIdx != -1) {
			methodName = methodName.substring(lastDotIdx + 1);
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				httpUtil.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClass(cache, className);

			if (cls == null) {
				httpUtil.sendError(exchange, 404, "Class " + className + " not found.");
				return;
			}

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
				httpUtil.sendError(exchange, 404, "Method '" + rawMethodName + "' not found in class " + className);
				return;
			}

			MethodNode methodNode = targetMethod.getMethodNode();
			if (methodNode == null) {
				httpUtil.sendError(exchange, 500, "Failed to get MethodNode for " + methodName);
				return;
			}

			List<SSAVar> sVars = methodNode.getSVars();
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
				httpUtil.sendError(exchange, 404, "No local variables found in method " + methodName + ". Code might be empty or not fully decompiled.");
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
				httpUtil.sendError(exchange, 404, "Variable '" + variableName + "' not found in method " + rawMethodName + ". Tip: Check if the variable exists in the source code.");
				return;
			}

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
                """.formatted(HttpUtil.escapeJson(className), HttpUtil.escapeJson(rawMethodName), HttpUtil.escapeJson(variableName), HttpUtil.escapeJson(newName));

			httpUtil.sendResponse(exchange, 200, resultJson);

		} catch (Exception e) {
			logger.error("Rename variable error", e);
			httpUtil.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}
}
