package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.ClassStructureBuilder;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.ManifestParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ResourceExplorerHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ResourceExplorerHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private final HttpHandler mainActivityHandler = new MainActivityHandler();
	private final HttpHandler mainApplicationHandler = new MainApplicationHandler();
	private final HttpHandler allResourceHandler = new AllResourceFileNameHandler();
	private final HttpHandler resourceFileHandler = new SourceHandler();
	private final HttpHandler manifestDetailHandler = new ManifestDetailHandler();
	private final HttpHandler resourceSearchHandler = new ResourceSearchHandler();

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendError(exchange, 503, "Service unavailable");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String action = HttpUtil.sanitizeAction(params.get("action"));

			if (action == null || action.isBlank()) {
				http.sendError(exchange, 400, "Missing required parameter: 'action'");
				return;
			}

			switch (action) {
				case "getMainActivity":
					mainActivityHandler.handle(exchange);
					break;
				case "getMainAppClasses":
					mainApplicationHandler.handle(exchange);
					break;
				case "getAllResourceNames":
					allResourceHandler.handle(exchange);
					break;
				case "getResourceFile":
					resourceFileHandler.handle(exchange);
					break;
				case "getManifestDetail":
					manifestDetailHandler.handle(exchange);
					break;
				case "searchResourceContent":
					resourceSearchHandler.handle(exchange);
					break;
				case "analyzeComponent":
					handleAnalyzeComponent(exchange);
					break;
				default:
					http.sendError(exchange, 400, "Invalid resource action: " + action);
			}
		} catch (Exception e) {
			logger.error("Resource Dispatcher Error", e);
			http.sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
		}
	}

	/**
	 * analyzeComponent: one-shot analysis returning manifest metadata + class structure + code.
	 * Replaces 3 separate API calls (getManifestDetail + getClassStructure + getClassCode).
	 */
	private void handleAnalyzeComponent(HttpExchange exchange) throws IOException {
		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String componentName = params.get("component_name");
		if (componentName == null || componentName.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: component_name");
			return;
		}

		try {
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("component_name", componentName);

			// 1. Manifest metadata for this component
			String xml = ManifestParser.getManifestXml(decompiler);
			if (xml != null) {
				Map<String, Object> manifest = new LinkedHashMap<>();
				String pkg = ManifestParser.extractPackageName(xml);
				String fullName = ManifestParser.normalize(pkg, componentName);
				result.put("component_name", fullName); // normalize

				// Find the component block in manifest
				String exported = findComponentExported(xml, componentName);
				if (exported != null) {
					manifest.put("exported", "true".equals(exported));
				}
				// Check if component has permission
				manifest.put("has_permission", hasComponentPermission(xml, componentName));
				result.put("manifest", manifest);

				componentName = fullName; // use normalized name for class lookup
			}

			// 2. Class structure
			var cache = CodeUtil.initClassCache(decompiler);
			JavaClass cls = CodeUtil.findClassDeeply(cache, componentName, decompiler);
			if (cls != null) {
				result.put("structure", ClassStructureBuilder.build(cls));

				// 3. Decompiled code
				String code = cls.getCode();
				if (code == null || code.isEmpty()) code = "/* Decompile failed */";
				result.put("code", code);
			} else {
				result.put("error", "Class not found: " + componentName);
			}

			http.sendResponse(exchange, 200, http.toJson(result));
		} catch (Exception e) {
			logger.error("analyzeComponent failed", e);
			http.sendError(exchange, 500, "Internal error: " + e.getMessage());
		}
	}

	private String findComponentExported(String xml, String name) {
		String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.')) : name;
		int idx = xml.indexOf(simpleName);
		if (idx < 0) return null;
		String block = xml.substring(idx, Math.min(xml.length(), idx + 500));
		int closeTag = block.indexOf(">");
		if (closeTag > 0) block = block.substring(0, closeTag);
		java.util.regex.Matcher m = Pattern.compile("android:exported\\s*=\\s*\"([^\"]+)\"").matcher(block);
		return m.find() ? m.group(1) : null;
	}

	private boolean hasComponentPermission(String xml, String name) {
		String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.')) : name;
		int idx = xml.indexOf(simpleName);
		if (idx < 0) return false;
		String block = xml.substring(idx, Math.min(xml.length(), idx + 500));
		int closeTag = block.indexOf(">");
		if (closeTag > 0) block = block.substring(0, closeTag);
		return block.contains("android:permission");
	}
}
