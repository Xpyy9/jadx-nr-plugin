package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.metadata.annotations.VarNode;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class VariableRenameHandler extends BaseRenameHandler {
	private static final Logger logger = LoggerFactory.getLogger(VariableRenameHandler.class);
	private final MainWindow mainWindow;

	public VariableRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	protected String validateParams(Map<String, String> params) {
		String className = params.get("class_name");
		String rawMethodName = params.get("method_name");
		String variableName = params.get("variable_name");
		String newName = params.get("new_name");
		if (className == null || className.isBlank()
				|| rawMethodName == null || rawMethodName.isBlank()
				|| variableName == null || variableName.isBlank()
				|| newName == null || newName.isBlank()) {
			return "Missing required parameters: class_name, method_name, variable_name, new_name";
		}
		return null;
	}

	@Override
	protected Map<String, Object> doRename(JadxDecompiler decompiler, Map<String, String> params) throws Exception {
		String className = params.get("class_name");
		String rawMethodName = params.get("method_name");
		String variableName = params.get("variable_name");
		String newName = params.get("new_name");
		String regStr = params.get("reg");
		String ssaStr = params.get("ssa");

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

		JavaClass cls = findClassOrThrow(decompiler, className);
		JavaMethod targetMethod = CodeUtil.findMethod(cls, methodName + (signature != null ? signature : ""));

		if (targetMethod == null) {
			throw RenameException.notFound("Method '" + rawMethodName + "' not found in class " + className);
		}

		MethodNode methodNode = targetMethod.getMethodNode();
		if (methodNode == null) {
			throw RenameException.internal("Failed to get MethodNode for " + methodName);
		}

		List<SSAVar> sVars = methodNode.getSVars();
		if (sVars == null || sVars.isEmpty()) {
			logger.info("SSA variables empty for method {}, forcing reload...", targetMethod.getName());
			cls.getClassNode().unload();
			cls.getClassNode().root().getProcessClasses().forceProcess(cls.getClassNode());
			MethodNode newMethodNode = cls.getClassNode().searchMethodByShortName(targetMethod.getName());
			if (newMethodNode != null) {
				methodNode = newMethodNode;
				sVars = methodNode.getSVars();
			}
		}

		if (sVars == null || sVars.isEmpty()) {
			throw RenameException.notFound("No local variables found in method " + methodName + ". Code might be empty or not fully decompiled.");
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
					CodeUtil.recordRename(newName, variableName);
					renamed = true;
					break;
				}
			}
		}

		if (!renamed) {
			throw RenameException.notFound("Variable '" + variableName + "' not found in method " + rawMethodName + ". Tip: Check if the variable exists in the source code.");
		}

		logger.info("Renamed variable {} to {} in {}", variableName, newName, targetMethod.getName());

		return successResult("Successfully renamed local variable", Map.of(
				"class_name", className,
				"method_name", rawMethodName,
				"old_variable_name", variableName,
				"new_variable_name", newName
		));
	}
}
