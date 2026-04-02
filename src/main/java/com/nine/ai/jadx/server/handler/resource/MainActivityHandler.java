package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivityHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(MainActivityHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendResponse(exchange, 500, "Decompiler not available");
			return;
		}

		try {
			// 1. 获取 AndroidManifest.xml 资源
			ResourceFile manifestRes = decompiler.getResources().stream()
					.filter(res -> "AndroidManifest.xml".equals(res.getOriginalName()))
					.findFirst().orElse(null);

			if (manifestRes == null) {
				http.sendResponse(exchange, 404, "AndroidManifest.xml not found");
				return;
			}

			String xml = JadxUtil.getResourceContent(manifestRes);
			if (xml == null || xml.isBlank()) {
				http.sendResponse(exchange, 500, "Failed to parse AndroidManifest.xml content");
				return;
			}

			// 2. 模仿参考代码的解析逻辑：提取包名并识别所有 Launcher 活动
			String pkg = extractPackage(xml);
			List<String> candidates = findLauncherActivities(xml, pkg);

			if (candidates.isEmpty()) {
				http.sendResponse(exchange, 404, "No activity with MAIN action and LAUNCHER category found in Manifest");
				return;
			}

			// 3. 验证并提取 JavaClass (这是参考代码中最关键的一步)
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass mainActivityClass = null;
			String finalName = null;

			for (String activityName : candidates) {
				JavaClass clazz = CodeUtil.findClass(cache, activityName);
				if (clazz != null && !clazz.getCode().isBlank()) {
					mainActivityClass = clazz;
					finalName = activityName;
					break; // 找到第一个有效的，立即退出
				}
			}

			if (mainActivityClass == null) {
				http.sendResponse(exchange, 404, "Found Launcher entries " + candidates + " but failed to get JavaClass source.");
				return;
			}

			// 4. 封装结果返回 (与参考代码结构保持一致)
			String code = mainActivityClass.getCode();
			http.sendResponse(exchange, 200, "// MainActivity: " + finalName + "\n\n" + code);

		} catch (Exception e) {
			logger.error("Error identifying MainActivity", e);
			http.sendResponse(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * 参考逻辑：从 Manifest 中提取所有可能是主入口的 Activity 全限定名
	 */
	private List<String> findLauncherActivities(String xml, String pkg) {
		List<String> activities = new ArrayList<>();

		// 关键改进：通过分割 <activity 标签来规避正则贪婪匹配问题
		// 每一个块都代表一个 activity 的定义范围
		String[] blocks = xml.split("<activity");

		for (String block : blocks) {
			// 检查该块是否包含启动特征
			if (block.contains("android.intent.action.MAIN") &&
					block.contains("android.intent.category.LAUNCHER")) {

				// 在这个特定的块里提取 android:name
				Pattern p = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");
				Matcher m = p.matcher(block);
				if (m.find()) {
					activities.add(normalize(pkg, m.group(1)));
				}
			}
		}
		return activities;
	}

	private String extractPackage(String xml) {
		Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
		return m.find() ? m.group(1) : "";
	}

	private String normalize(String pkg, String cls) {
		if (cls.startsWith(".")) return pkg + cls;
		if (!cls.contains(".")) return pkg + "." + cls;
		return cls;
	}
}
