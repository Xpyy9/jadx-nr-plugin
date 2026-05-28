package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class ClassRenameHandler extends BaseRenameHandler {
	private static final Logger logger = LoggerFactory.getLogger(ClassRenameHandler.class);
	private final MainWindow mainWindow;

	public ClassRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	protected String validateParams(Map<String, String> params) {
		String className = params.get("class_name");
		String newName = params.get("new_name");
		if (className == null || className.isBlank() || newName == null || newName.isBlank()) {
			return "Missing required parameters: class_name and new_name";
		}
		return null;
	}

	@Override
	protected Map<String, Object> doRename(JadxDecompiler decompiler, Map<String, String> params) throws Exception {
		String className = params.get("class_name");
		String newName = params.get("new_name");

		JavaClass cls = findClassOrThrow(decompiler, className);

		ICodeNodeRef nodeRef = cls.getCodeNodeRef();
		NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
		event.setRenameNode(cls.getClassNode());
		event.setResetName(newName.isEmpty());

		mainWindow.events().send(event);
		CodeUtil.recordRename(newName, cls.getName());
		logger.info("Renamed Class {} to {}", cls.getName(), newName);

		return successResult("Successfully renamed class", Map.of(
				"old_name", cls.getName(),
				"new_name", newName
		));
	}
}
