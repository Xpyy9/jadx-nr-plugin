package com.nine.ai.jadx.util;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 共享类结构提取。
 * 替代 ClassStructureHandler 和 CompositeHandler.buildStructure() 中的重复逻辑。
 * 返回结构化对象（含 access flags），精准对接 Knowledge Graph。
 */
public class ClassStructureBuilder {
	private static final Logger logger = LoggerFactory.getLogger(ClassStructureBuilder.class);

	/**
	 * 提取 JavaClass 的结构信息（字段、方法、继承关系）。
	 */
	public static Map<String, Object> build(JavaClass cls) {
		List<Map<String, Object>> fields = new ArrayList<>();
		for (JavaField field : cls.getFields()) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("name", field.getName());
			try {
				f.put("type", field.getFieldNode().getType().toString());
			} catch (Exception e) {
				f.put("type", "unknown");
			}
			try {
				int flags = field.getFieldNode().getAccessFlags().rawValue();
				f.put("access", decodeAccessFlags(flags));
				f.put("is_static", (flags & 0x0008) != 0);
			} catch (Exception ignored) {}
			fields.add(f);
		}

		List<Map<String, Object>> methods = new ArrayList<>();
		for (JavaMethod method : cls.getMethods()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", method.getName());
			try {
				m.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			} catch (Exception e) {
				m.put("signature", method.getName());
			}
			try {
				int flags = method.getMethodNode().getAccessFlags().rawValue();
				m.put("access", decodeAccessFlags(flags));
			} catch (Exception ignored) {}
			methods.add(m);
		}

		String superClass = "java.lang.Object";
		List<String> interfaces = new ArrayList<>();
		try {
			if (cls.getClassNode().getSuperClass() != null) {
				superClass = cls.getClassNode().getSuperClass().getObject();
			}
			if (cls.getClassNode().getInterfaces() != null) {
				for (var iface : cls.getClassNode().getInterfaces()) {
					interfaces.add(iface.getObject());
				}
			}
		} catch (Exception e) {
			logger.debug("Failed to get superclass/interfaces for {}", cls.getFullName(), e);
		}

		Map<String, Object> structure = new LinkedHashMap<>();
		structure.put("class_name", cls.getFullName());
		structure.put("super_class", superClass);
		structure.put("implements", interfaces);
		structure.put("fields", fields);
		structure.put("methods", methods);
		return structure;
	}

	static String decodeAccessFlags(int flags) {
		List<String> parts = new ArrayList<>();
		if ((flags & 0x0001) != 0) parts.add("public");
		if ((flags & 0x0002) != 0) parts.add("private");
		if ((flags & 0x0004) != 0) parts.add("protected");
		if ((flags & 0x0008) != 0) parts.add("static");
		if ((flags & 0x0010) != 0) parts.add("final");
		if ((flags & 0x0020) != 0) parts.add("synchronized");
		if ((flags & 0x0040) != 0) parts.add("volatile");
		if ((flags & 0x0100) != 0) parts.add("native");
		if ((flags & 0x0400) != 0) parts.add("abstract");
		return parts.isEmpty() ? "package-private" : String.join(" ", parts);
	}
}
