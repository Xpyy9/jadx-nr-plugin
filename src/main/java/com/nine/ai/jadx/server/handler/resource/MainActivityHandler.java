package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivityHandler implements HttpHandler {
	/**
	 * 自动解析应用的 AndroidManifest.xml 文件，寻找配置了 MAIN 和 LAUNCHER 属性的主 Activity（App 启动时显示的第一个界面），并直接返回该类的完整 Java 源代码。
	 * 【使用建议】：当你的任务是“分析应用启动逻辑”、“寻找初始化代码”或“定位脱壳切入点”时，这是最首选的工具。它能让你一键直达应用的业务入口，免去手动拉取和解析清单文件的繁琐步骤。
	 */
	private final HttpUtil http = HttpUtil.getInstance();
	private static final int MAX_CODE_LENGTH = 150000; // 安全限制，最大字符长度

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendResponse(exchange, 405, "Only GET allowed");
			return;
		}
		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				sendError(exchange, 500, "Decompiler not available");
				return;
			}

			ResourceFile manifest = findManifest(decompiler);
			if (manifest == null) {
				sendError(exchange, 404, "AndroidManifest.xml not found");
				return;
			}

			String xml = JadxUtil.getResourceContent(manifest);
			String mainActivity = findMainActivityFromManifest(xml);
			if (mainActivity == null) {
				sendError(exchange, 404, "Main activity not found in manifest");
				return;
			}
			Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
			JavaClass activityClass = CodeUtil.findClass(cache, mainActivity);

			if (activityClass == null) {
				sendError(exchange, 404, "Main activity class not found: " + mainActivity);
				return;
			}

			String code = activityClass.getCode();
			if (code != null && code.length() > MAX_CODE_LENGTH) {
				code = code.substring(0, MAX_CODE_LENGTH) + "\n[TRUNCATED: Content too long. Please use getClassCode for specific methods.]";
			}

			String json = buildResult(activityClass.getFullName(), code);
			http.sendResponse(exchange, 200, json);

		} catch (Exception e) {
			sendError(exchange, 500, "Error: " + e.getMessage());
		}
	}

	private ResourceFile findManifest(JadxDecompiler decompiler) {
		Map<String, ResourceFile> cache = JadxUtil.getResourceCache(decompiler);
		if (cache != null) {
			ResourceFile exactMatch = cache.get("AndroidManifest.xml");
			if (exactMatch != null) return exactMatch;
			for (ResourceFile res : cache.values()) {
				if (res.getOriginalName() != null && res.getOriginalName().endsWith("AndroidManifest.xml")) {
					return res;
				}
			}
		}
		return null;
	}

	private String findMainActivityFromManifest(String xml) {
		if (xml == null) return null;

		Pattern pattern = Pattern.compile(
				"<activity[^>]*name\\s*=\\s*\"([^\"]+)\"[^<]*" +
						"<intent-filter>.*?<action[^>]*android.intent.action.MAIN[^>]*>.*?" +
						"<category[^>]*android.intent.category.LAUNCHER[^>]*>.*?</intent-filter>",
				Pattern.DOTALL | Pattern.CASE_INSENSITIVE
		);

		Matcher matcher = pattern.matcher(xml);
		if (matcher.find()) {
			String name = matcher.group(1).trim();
			if (name.startsWith(".")) {
				String pkg = extractPackage(xml);
				name = pkg + name;
			}
			return name;
		}
		return null;
	}

	private String extractPackage(String xml) {
		Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
		return m.find() ? m.group(1) : "";
	}

	private String buildResult(String name, String content) {
		return """
                {
                  "name": "%s",
                  "type": "code/java",
                  "content": "%s"
                }
                """.formatted(escape(name), escape(content));
	}

	private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
		http.sendResponse(exchange, code, "{\"error\":\"%s\"}".formatted(escape(msg)));
	}

	private String escape(String s) {
		return s == null ? "" : s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t")
				.replace("\b", "\\b")
				.replace("\f", "\\f");
	}
}
