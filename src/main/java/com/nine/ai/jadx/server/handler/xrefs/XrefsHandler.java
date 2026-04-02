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
	/**
	 * 获取交叉引用（Xrefs/Find Usages）- 增强内存保护版。
	 * 【优化说明】：
	 * 1. 引入 XREF_HARD_LIMIT (2000条)：防止扫描底层基础库函数时崩溃。
	 * 2. 增加 is_overflow 标识：主动告知 Agent 数据已截断，建议缩小搜索范围。
	 * 3. 结构化流式收集：减少中间大集合的产生。
	 */
	private final HttpUtil http = HttpUtil.getInstance();
	private static final int XREF_HARD_LIMIT = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String clsName = params.get("class");
		String methodName = params.get("method");
		String fieldName = params.get("field");

		if (clsName == null || clsName.isBlank()) {
			sendError(exchange, 400, "Required parameter: class");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClass(cache, clsName);

			if (cls == null) {
				sendError(exchange, 404, "Class not found: " + clsName);
				return;
			}

			List<String> results = new ArrayList<>();
			String xrefType = "class-xrefs";
			boolean isOverflow = false;

			// ======================
			// 1. 查询字段引用 (带硬截断)
			// ======================
			if (fieldName != null && !fieldName.isBlank()) {
				xrefType = "field-xrefs";
				JavaField targetField = cls.getFields().stream()
						.filter(f -> fieldName.equals(f.getName()))
						.findFirst().orElse(null);

				if (targetField == null) {
					sendError(exchange, 404, "Field not found: " + fieldName);
					return;
				}
				isOverflow = collectFromNodes(targetField.getFieldNode().getUseIn(), results);
			}
			// ======================
			// 2. 查询方法引用 (带硬截断)
			// ======================
			else if (methodName != null && !methodName.isBlank()) {
				xrefType = "method-xrefs";
				JavaMethod targetMethod = cls.getMethods().stream()
						.filter(m -> methodName.equals(m.getName()))
						.findFirst().orElse(null);

				if (targetMethod == null) {
					sendError(exchange, 404, "Method not found: " + methodName);
					return;
				}
				isOverflow = collectFromNodes(targetMethod.getMethodNode().getUseIn(), results);
			}
			// ======================
			// 3. 查询类引用 (带硬截断)
			// ======================
			else {
				ClassNode node = cls.getClassNode();
				// 合并类引用和方法内对类的引用
				isOverflow = collectClassXrefs(node, results);
			}

			// 分页处理
			int offset = parseInt(params.get("offset"), 0);
			int limit = parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> pageResult = PageUtil.paginate(
					results,
					offset,
					limit,
					xrefType,
					"references",
					item -> item
			);

			if (isOverflow) {
				Map<String, Object> pagination = (Map<String, Object>) pageResult.get("pagination");
				pagination.put("is_overflow", true);
				pagination.put("warning", "Result truncated to " + XREF_HARD_LIMIT + " entries to prevent OOM.");
			}

			http.sendResponse(exchange, 200, toJsonObj(pageResult));

		} catch (Exception e) {
			sendError(exchange, 500, "Xrefs error: " + e.getMessage());
		}
	}

	/**
	 * 安全收集 MethodNode 引用，支持硬截断
	 */
	private boolean collectFromNodes(Collection<MethodNode> nodes, List<String> results) {
		int count = 0;
		for (MethodNode m : nodes) {
			if (count >= XREF_HARD_LIMIT) return true;
			results.add(m.getParentClass().getFullName() + " | " + getMethodSignature(m));
			count++;
		}
		return false;
	}

	/**
	 * 安全收集 Class 级别引用
	 */
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

	private int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
		http.sendResponse(exchange, code, "{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}");
	}

	private String toJsonObj(Object v) {
		if (v == null) return "null";
		if (v instanceof String) {
			return "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		} else if (v instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			List<?> list = (List<?>) v;
			for (int i = 0; i < list.size(); i++) {
				sb.append(toJsonObj(list.get(i)));
				if (i < list.size() - 1) sb.append(",");
			}
			sb.append("]");
			return sb.toString();
		} else if (v instanceof Map) {
			StringBuilder sb = new StringBuilder("{");
			Map<?, ?> map = (Map<?, ?>) v;
			int i = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				sb.append("\"").append(entry.getKey()).append("\":").append(toJsonObj(entry.getValue()));
				if (i < map.size() - 1) sb.append(",");
				i++;
			}
			sb.append("}");
			return sb.toString();
		} else {
			return v.toString();
		}
	}
}
