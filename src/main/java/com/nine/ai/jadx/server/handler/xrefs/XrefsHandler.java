package com.nine.ai.jadx.server.handler.xrefs;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.*;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class XrefsHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(XrefsHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();
	private static final int XREF_HARD_LIMIT = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String clsName = params.get("class");
		String methodName = params.get("method");
		String fieldName = params.get("field");

		if (clsName == null || clsName.isBlank()) {
			http.sendError(exchange, 400, "Required parameter: class");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClassDeeply(cache, clsName, decompiler);

			if (cls == null) {
				http.sendError(exchange, 404, "Class not found: " + clsName);
				return;
			}

			List<Map<String, String>> results = new ArrayList<>();
			String xrefType = "class-xrefs";
			String target = cls.getFullName();
			boolean isOverflow = false;

			if (fieldName != null && !fieldName.isBlank()) {
				xrefType = "field-xrefs";
				target = cls.getFullName() + "." + fieldName;
				JavaField targetField = CodeUtil.findField(cls, fieldName);

				if (targetField == null) {
					List<String> available = cls.getFields().stream()
							.map(JavaField::getName).collect(Collectors.toList());
					logger.warn("Field '{}' not found in {}. Available: {}", fieldName, cls.getFullName(), available);
					http.sendError(exchange, 404, "Field not found: " + fieldName);
					return;
				}
				isOverflow = collectFromNodes(targetField.getFieldNode().getUseIn(), results);
			} else if (methodName != null && !methodName.isBlank()) {
				xrefType = "method-xrefs";
				target = cls.getFullName() + "." + methodName;
				JavaMethod targetMethod = CodeUtil.findMethod(cls, methodName);

				if (targetMethod == null) {
					List<String> available = cls.getMethods().stream()
							.map(JavaMethod::getName).collect(Collectors.toList());
					logger.warn("Method '{}' not found in {}. Available: {}", methodName, cls.getFullName(), available);
					http.sendError(exchange, 404, "Method not found: " + methodName
							+ ". Available: " + available);
					return;
				}
				isOverflow = collectFromNodes(targetMethod.getMethodNode().getUseIn(), results);
			} else {
				isOverflow = collectClassXrefs(cls.getClassNode(), results);
			}

			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> pageResult = PageUtil.paginate(
					results, offset, limit, xrefType, "references", item -> item
			);
			pageResult.put("target", target);

			if (isOverflow) {
				@SuppressWarnings("unchecked")
				Map<String, Object> pagination = (Map<String, Object>) pageResult.get("pagination");
				pagination.put("is_overflow", true);
				pagination.put("warning", "Result truncated to " + XREF_HARD_LIMIT + " entries to prevent OOM.");
			}

			http.sendResponse(exchange, 200, http.toJson(pageResult));

		} catch (Exception e) {
			http.sendError(exchange, 500, "Xrefs error: " + e.getMessage());
		}
	}

	private boolean collectFromNodes(Collection<MethodNode> nodes, List<Map<String, String>> results) {
		int count = 0;
		for (MethodNode m : nodes) {
			if (count >= XREF_HARD_LIMIT) return true;
			Map<String, String> entry = new LinkedHashMap<>();
			entry.put("class_name", m.getParentClass().getFullName());
			entry.put("method_name", m.getName());
			entry.put("method_signature", getMethodSignature(m));
			results.add(entry);
			count++;
		}
		return false;
	}

	private boolean collectClassXrefs(ClassNode node, List<Map<String, String>> results) {
		int count = 0;
		for (ClassNode c : node.getUseIn()) {
			if (count >= XREF_HARD_LIMIT) return true;
			Map<String, String> entry = new LinkedHashMap<>();
			entry.put("class_name", c.getFullName());
			entry.put("ref_type", "class");
			results.add(entry);
			count++;
		}
		for (MethodNode m : node.getUseInMth()) {
			if (count >= XREF_HARD_LIMIT) return true;
			Map<String, String> entry = new LinkedHashMap<>();
			entry.put("class_name", m.getParentClass().getFullName());
			entry.put("method_name", m.getName());
			entry.put("method_signature", getMethodSignature(m));
			entry.put("ref_type", "method");
			results.add(entry);
			count++;
		}
		return false;
	}

	private String getMethodSignature(MethodNode m) {
		try {
			return m.getMethodInfo().getShortId();
		} catch (Exception e) {
			return m.getName();
		}
	}
}
