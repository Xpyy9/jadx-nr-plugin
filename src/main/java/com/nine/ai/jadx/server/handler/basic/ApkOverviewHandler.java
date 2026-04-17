package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.server.PluginServer;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApkOverviewHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ApkOverviewHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	/** Cached JSON string of the APK overview, built once at startup */
	private volatile String cachedOverviewJson = null;

	private static final int MAX_WAIT_RETRIES = 30;
	private static final long RETRY_INTERVAL_MS = 2000; // 2s per retry, up to ~60s total

	/**
	 * Pre-build the APK overview and cache the result as JSON.
	 * Retries until both the decompiler is ready AND the class list is fully
	 * populated (avoids ConcurrentModificationException during JADX loading).
	 */
	public void preload() {
		logger.info("ApkOverviewHandler: waiting for decompiler to be ready...");
		for (int i = 0; i < MAX_WAIT_RETRIES; i++) {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler(false);
				if (decompiler != null) {
					cachedOverviewJson = buildOverviewJson(decompiler);
					logger.info("ApkOverviewHandler: APK overview preloaded successfully");
					return;
				}
			} catch (Exception e) {
				// Decompiler returned but data not fully ready yet
				// (e.g. ConcurrentModificationException) — just retry
				logger.debug("ApkOverviewHandler: preload attempt {} failed: {}", i + 1, e.getMessage());
			}
			try {
				Thread.sleep(RETRY_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.warn("ApkOverviewHandler: preload interrupted");
				return;
			}
		}
		logger.warn("ApkOverviewHandler: decompiler not fully ready after waiting, will lazy-load on first request");
	}

	/**
	 * Clear the cached overview. Called when caches are invalidated
	 * so the next request will trigger a lazy rebuild.
	 */
	public void clearCache() {
		cachedOverviewJson = null;
		logger.info("ApkOverviewHandler: cache cleared");
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendError(exchange, 503, "Service unavailable");
			return;
		}

		try {
			String json = cachedOverviewJson;
			if (json == null) {
				// Lazy rebuild if cache was cleared or preload hasn't finished yet
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					http.sendError(exchange, 500, "Decompiler not available");
					return;
				}
				json = buildOverviewJson(decompiler);
				cachedOverviewJson = json;
			}
			http.sendResponse(exchange, 200, json);
		} catch (Exception e) {
			logger.error("APK overview failed", e);
			http.sendError(exchange, 500, "Failed to generate APK overview: " + e.getMessage());
		}
	}

	// ====================== Internal: build the overview JSON ======================

	private String buildOverviewJson(JadxDecompiler decompiler) {
		Map<String, Object> overview = new LinkedHashMap<>();

		// 1. Class & method counts
		List<JavaClass> allClasses = decompiler.getClassesWithInners();
		int totalClasses = allClasses.size();
		int totalMethods = 0;
		for (JavaClass cls : allClasses) {
			try {
				totalMethods += cls.getMethods().size();
			} catch (Exception ignored) {}
		}

		// 2. Resource count
		List<ResourceFile> resources = decompiler.getResources();
		int totalResources = resources != null ? resources.size() : 0;

		// 3. Parse AndroidManifest.xml
		String manifestXml = null;
		if (resources != null) {
			for (ResourceFile res : resources) {
				if ("AndroidManifest.xml".equals(res.getOriginalName())) {
					manifestXml = JadxUtil.getResourceContent(res);
					break;
				}
			}
		}

		String packageName = "";
		List<String> activities = new ArrayList<>();
		List<String> services = new ArrayList<>();
		List<String> receivers = new ArrayList<>();
		List<String> providers = new ArrayList<>();
		List<String> permissions = new ArrayList<>();
		int minSdk = -1;
		int targetSdk = -1;

		if (manifestXml != null && !manifestXml.isBlank()) {
			packageName = extractAttr(manifestXml, "<manifest[^>]+package\\s*=\\s*\"([^\"]+)\"");

			activities = extractComponents(manifestXml, "<activity", packageName);
			services = extractComponents(manifestXml, "<service", packageName);
			receivers = extractComponents(manifestXml, "<receiver", packageName);
			providers = extractComponents(manifestXml, "<provider", packageName);

			permissions = extractPermissions(manifestXml);

			String minSdkStr = extractAttr(manifestXml, "android:minSdkVersion\\s*=\\s*\"([^\"]+)\"");
			String targetSdkStr = extractAttr(manifestXml, "android:targetSdkVersion\\s*=\\s*\"([^\"]+)\"");
			minSdk = HttpUtil.parseInt(minSdkStr, -1);
			targetSdk = HttpUtil.parseInt(targetSdkStr, -1);
		}

		overview.put("package_name", packageName);
		overview.put("total_classes", totalClasses);
		overview.put("total_methods", totalMethods);
		overview.put("total_resources", totalResources);

		Map<String, Object> components = new LinkedHashMap<>();
		components.put("activities", activities);
		components.put("services", services);
		components.put("receivers", receivers);
		components.put("providers", providers);
		overview.put("components", components);

		overview.put("permissions", permissions);
		if (minSdk > 0) overview.put("min_sdk", minSdk);
		if (targetSdk > 0) overview.put("target_sdk", targetSdk);

		return http.toJson(overview);
	}

	// ====================== XML helpers (unchanged) ======================

	private List<String> extractComponents(String xml, String tagPrefix, String pkg) {
		List<String> result = new ArrayList<>();
		Pattern namePattern = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");

		String[] blocks = xml.split(tagPrefix);
		for (int i = 1; i < blocks.length; i++) {
			String block = blocks[i];
			int closeIdx = block.indexOf('>');
			String attrs = closeIdx > 0 ? block.substring(0, closeIdx) : block;
			Matcher m = namePattern.matcher(attrs);
			if (m.find()) {
				result.add(normalize(pkg, m.group(1)));
			}
		}
		return result;
	}

	private List<String> extractPermissions(String xml) {
		List<String> perms = new ArrayList<>();
		Pattern p = Pattern.compile("<uses-permission[^>]+android:name\\s*=\\s*\"([^\"]+)\"");
		Matcher m = p.matcher(xml);
		while (m.find()) {
			perms.add(m.group(1));
		}
		return perms;
	}

	private String extractAttr(String xml, String regex) {
		Matcher m = Pattern.compile(regex).matcher(xml);
		return m.find() ? m.group(1) : "";
	}

	private String normalize(String pkg, String cls) {
		if (cls.startsWith(".")) return pkg + cls;
		if (!cls.contains(".")) return pkg + "." + cls;
		return cls;
	}
}
