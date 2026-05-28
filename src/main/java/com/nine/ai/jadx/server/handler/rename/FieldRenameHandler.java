package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.util.CodeUtil;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class FieldRenameHandler extends BaseRenameHandler {
	private static final Logger logger = LoggerFactory.getLogger(FieldRenameHandler.class);
	private final MainWindow mainWindow;

	public FieldRenameHandler(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
	}

	@Override
	protected String validateParams(Map<String, String> params) {
		String className = params.get("class_name");
		String fieldName = params.get("field_name");
		String newName = params.get("new_field_name");
		if (newName == null || newName.isBlank()) newName = params.get("new_name");
		if (className == null || className.isBlank() || fieldName == null || fieldName.isBlank()
				|| newName == null || newName.isBlank()) {
			return "Missing required parameters: class_name, field_name, and new_name";
		}
		return null;
	}

	@Override
	protected Map<String, Object> doRename(JadxDecompiler decompiler, Map<String, String> params) throws Exception {
		String className = params.get("class_name");
		String fieldName = params.get("field_name");
		String newName = params.get("new_field_name");
		if (newName == null || newName.isBlank()) newName = params.get("new_name");

		JavaClass cls = findClassOrThrow(decompiler, className);
		JavaField targetField = CodeUtil.findField(cls, fieldName);

		if (targetField == null) {
			throw RenameException.notFound("Field '" + fieldName + "' not found in class " + className);
		}

		ICodeNodeRef nodeRef = targetField.getCodeNodeRef();
		NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, targetField.getName(), newName);
		event.setRenameNode(targetField.getFieldNode());
		event.setResetName(newName.isEmpty());

		mainWindow.events().send(event);
		CodeUtil.recordRename(newName, targetField.getName());
		logger.info("Renamed field {} in class {} to {}", fieldName, className, newName);

		return successResult("Successfully renamed field", Map.of(
				"class_name", className,
				"old_field_name", fieldName,
				"new_field_name", newName
		));
	}
}
