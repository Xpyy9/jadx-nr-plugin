package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.nodes.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Composite handler for multi-step queries that would otherwise require
 * multiple HTTP round-trips. Reduces executor tool-call iterations.
 */
public class CompositeHandler {
	private static final Logger logger = LoggerFactory.getLogger(CompositeHandler.class);
	private static final HttpUtil http = HttpUtil.getInstance();
	private static final int XREF_HARD_LIMIT = 200;
	private static final int BATCH_MAX = 5;

	/**
	 * getClassWithStructure: returns class structure + decompiled source in one call.
	 */
	public static void handleClassWithStructure(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String className = params.get("class_name");
		if (className == null || className.isBlank()) {
			className = params.get("code_name");
		}
		if (className == null || className.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: class_name or code_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);
			if (cls == null) {
				http.sendError(exchange, 404, "Class not found: " + className);
				return;
			}

			Map<String, Object> result = new LinkedHashMap<>();

			// Structure part
			Map<String, Object> structure = buildStructure(cls);
			result.put("structure", structure);

			// Code part
			String code = cls.getCode();
			if (code == null || code.isEmpty()) code = "/* Decompile failed */";
			result.put("class_name", cls.getFullName());
			result.put("code", code);

			http.sendResponse(exchange, 200, http.toJson(result));
		} catch (Exception e) {
			logger.error("getClassWithStructure failed", e);
			http.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * batchGetClassCode: fetch decompiled code for up to 5 classes in one call.
	 */
	public static void handleBatchGetClassCode(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String codeNames = params.get("code_names");
		if (codeNames == null || codeNames.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: code_names (comma-separated)");
			return;
		}

		String[] names = codeNames.split(",");
		if (names.length > BATCH_MAX) {
			http.sendError(exchange, 400, "Maximum " + BATCH_MAX + " classes per batch");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}
			var cache = CodeUtil.initClassCache(decompiler);

			List<Map<String, Object>> results = new ArrayList<>();
			for (String name : names) {
				String trimmed = name.trim();
				if (trimmed.isEmpty()) continue;

				Map<String, Object> entry = new LinkedHashMap<>();
				JavaClass cls = CodeUtil.findClassDeeply(cache, trimmed, decompiler);
				if (cls == null) {
					entry.put("class_name", trimmed);
					entry.put("error", "Class not found");
				} else {
					String code = cls.getCode();
					if (code == null || code.isEmpty()) code = "/* Decompile failed */";
					entry.put("class_name", cls.getFullName());
					entry.put("code", code);
				}
				results.add(entry);
			}

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("type", "batch-class-code");
			response.put("count", results.size());
			response.put("classes", results);

			http.sendResponse(exchange, 200, http.toJson(response));
		} catch (Exception e) {
			logger.error("batchGetClassCode failed", e);
			http.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * getMethodWithCallers: returns method source code + its callers (xrefs) in one call.
	 */
	public static void handleMethodWithCallers(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String className = params.get("class_name");
		String methodName = params.get("method_name");

		if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameters: class_name, method_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);
			if (cls == null) {
				http.sendError(exchange, 404, "Class not found: " + className);
				return;
			}

			JavaMethod method = CodeUtil.findMethod(cls, methodName);
			if (method == null) {
				http.sendError(exchange, 404, "Method not found: " + methodName + " in " + className);
				return;
			}

			Map<String, Object> result = new LinkedHashMap<>();

			// Method code
			String code = method.getCodeStr();
			if (code == null || code.isEmpty()) code = "/* Method decompile failed */";
			result.put("class_name", cls.getFullName());
			result.put("method_name", method.getName());
			result.put("method_code", code);

			// Callers (xrefs)
			List<String> callers = new ArrayList<>();
			Collection<MethodNode> useIn = method.getMethodNode().getUseIn();
			int count = 0;
			boolean overflow = false;
			for (MethodNode m : useIn) {
				if (count >= XREF_HARD_LIMIT) {
					overflow = true;
					break;
				}
				callers.add(m.getParentClass().getFullName() + " | " + getMethodSig(m));
				count++;
			}

			result.put("callers", callers);
			result.put("caller_count", useIn.size());
			if (overflow) {
				result.put("caller_overflow", true);
				result.put("caller_warning", "Truncated to " + XREF_HARD_LIMIT + " callers, total: " + useIn.size());
			}

			http.sendResponse(exchange, 200, http.toJson(result));
		} catch (Exception e) {
			logger.error("getMethodWithCallers failed", e);
			http.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	// ====================== Helpers ======================

	private static Map<String, Object> buildStructure(JavaClass cls) {
		List<String> fields = new ArrayList<>();
		for (JavaField field : cls.getFields()) {
			try {
				String typeStr = field.getFieldNode().getType().toString();
				fields.add(typeStr + " " + field.getName());
			} catch (Exception e) {
				fields.add(field.getName());
			}
		}

		List<String> methods = new ArrayList<>();
		for (JavaMethod method : cls.getMethods()) {
			try {
				methods.add(method.getMethodNode().getMethodInfo().getShortId());
			} catch (Exception e) {
				methods.add(method.getName());
			}
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
			logger.debug("Failed to get superclass/interfaces", e);
		}

		Map<String, Object> structure = new LinkedHashMap<>();
		structure.put("class_name", cls.getFullName());
		structure.put("super_class", superClass);
		structure.put("implements", interfaces);
		structure.put("fields", fields);
		structure.put("methods", methods);
		return structure;
	}

	private static String getMethodSig(MethodNode m) {
		try {
			return m.getMethodInfo().getShortId();
		} catch (Exception e) {
			return m.getName();
		}
	}
}
