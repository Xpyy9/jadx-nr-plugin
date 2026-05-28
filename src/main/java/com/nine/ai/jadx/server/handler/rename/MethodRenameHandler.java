package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class MethodRenameHandler extends BaseRenameHandler {
	private static final Logger logger = LoggerFactory.getLogger(MethodRenameHandler.class);
	private final MainWindow mainWindow;

	public MethodRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	protected String validateParams(Map<String, String> params) {
		String rawMethodName = params.get("method_name");
		String newName = params.get("new_name");
		if (rawMethodName == null || rawMethodName.isBlank() || newName == null || newName.isBlank()) {
			return "Missing required parameters: method_name and new_name";
		}
		return null;
	}

	@Override
	protected Map<String, Object> doRename(JadxDecompiler decompiler, Map<String, String> params) throws Exception {
		String rawMethodName = params.get("method_name");
		String classNameParam = params.get("class_name");
		String newName = params.get("new_name");

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
			className = fullMethodPath.substring(0, lastDotIdx);
			methodName = fullMethodPath.substring(lastDotIdx + 1);
		} else if (classNameParam != null && !classNameParam.isBlank()) {
			className = classNameParam;
			methodName = fullMethodPath;
		} else {
			throw RenameException.badRequest("Cannot determine class. Either use full path in method_name (com.example.MyClass.myMethod) or provide class_name parameter.");
		}

		JavaClass cls = findClassOrThrow(decompiler, className);
		JavaMethod targetMethod = CodeUtil.findMethod(cls, methodName + (signature != null ? signature : ""));

		if (targetMethod == null) {
			throw RenameException.notFound("Method '" + methodName + "' not found in class " + className);
		}

		ICodeNodeRef nodeRef = targetMethod.getCodeNodeRef();
		NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetMethod.getName(), newName);
		event.setRenameNode(targetMethod.getMethodNode());
		event.setResetName(newName.isEmpty());

		mainWindow.events().send(event);
		CodeUtil.recordRename(newName, targetMethod.getName());
		logger.info("Renamed method {} in class {} to {}", methodName, className, newName);

		return successResult("Successfully renamed method", Map.of(
				"class_name", className,
				"old_method_name", methodName,
				"new_method_name", newName
		));
	}
}
