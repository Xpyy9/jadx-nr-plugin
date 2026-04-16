package com.nine.ai.jadx.server.handler.xrefs;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.*;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.IOException;
import java.util.*;

public class XrefsHandler implements HttpHandler {
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
			JavaClass cls = CodeUtil.findClass(cache, clsName);

			if (cls == null) {
				http.sendError(exchange, 404, "Class not found: " + clsName);
				return;
			}

			List<String> results = new ArrayList<>();
			String xrefType = "class-xrefs";
			boolean isOverflow = false;

			if (fieldName != null && !fieldName.isBlank()) {
				xrefType = "field-xrefs";
				JavaField targetField = cls.getFields().stream()
						.filter(f -> fieldName.equals(f.getName()))
						.findFirst().orElse(null);

				if (targetField == null) {
					http.sendError(exchange, 404, "Field not found: " + fieldName);
					return;
				}
				isOverflow = collectFromNodes(targetField.getFieldNode().getUseIn(), results);
			} else if (methodName != null && !methodName.isBlank()) {
				xrefType = "method-xrefs";
				JavaMethod targetMethod = cls.getMethods().stream()
						.filter(m -> methodName.equals(m.getName()))
						.findFirst().orElse(null);

				if (targetMethod == null) {
					http.sendError(exchange, 404, "Method not found: " + methodName);
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

	private boolean collectFromNodes(Collection<MethodNode> nodes, List<String> results) {
		int count = 0;
		for (MethodNode m : nodes) {
			if (count >= XREF_HARD_LIMIT) return true;
			results.add(m.getParentClass().getFullName() + " | " + getMethodSignature(m));
			count++;
		}
		return false;
	}

	private boolean collectClassXrefs(ClassNode node, List<String> results) {
		int count = 0;
		for (ClassNode c : node.getUseIn()) {
			if (count >= XREF_HARD_LIMIT) return true;
			results.add(c.getFullName());
			count++;
		}
		for (MethodNode m : node.getUseInMth()) {
			if (count >= XREF_HARD_LIMIT) return true;
			results.add(m.getParentClass().getFullName() + " | " + getMethodSignature(m));
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
