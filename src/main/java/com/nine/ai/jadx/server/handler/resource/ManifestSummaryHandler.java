package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.ManifestParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manifest 轻量摘要：返回紧凑结构化 JSON (~500 tokens)。
 * 用于 agent 预加载，替代每次调用完整 getManifestDetail。
 *
 * 输出内容：
 * - package_name, min/target SDK
 * - components_summary: name + type + exported + has_intent_filter（不展开 filter 细节）
 * - permissions 列表
 * - application 安全属性
 * - security_findings（自动检测结果）
 * - deep_links 计数 + 前 5 个预览
 */
public class ManifestSummaryHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(ManifestSummaryHandler.class);
    private final HttpUtil http = HttpUtil.getInstance();

    private static final Pattern ATTR_NAME = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_EXPORTED = Pattern.compile("android:exported\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_PERMISSION = Pattern.compile("android:permission\\s*=\\s*\"([^\"]+)\"");

    /** Cached JSON string, built once at preload */
    private volatile String cachedJson = null;

    private static final int MAX_WAIT_RETRIES = 30;
    private static final long RETRY_INTERVAL_MS = 2000;

    /**
     * Pre-build the manifest summary and cache it.
     * Called from PluginServer preload thread.
     */
    public void preload() {
        logger.info("ManifestSummaryHandler: waiting for decompiler to be ready...");
        for (int i = 0; i < MAX_WAIT_RETRIES; i++) {
            try {
                JadxDecompiler decompiler = JadxUtil.getDecompiler(false);
                if (decompiler != null) {
                    String xml = ManifestParser.getManifestXml(decompiler);
                    if (xml != null && !xml.isBlank()) {
                        cachedJson = buildSummaryJson(xml);
                        logger.info("ManifestSummaryHandler: manifest summary preloaded successfully");
                        return;
                    }
                }
            } catch (Exception e) {
                logger.debug("ManifestSummaryHandler: preload attempt {} failed: {}", i + 1, e.getMessage());
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("ManifestSummaryHandler: preload interrupted");
                return;
            }
        }
        logger.warn("ManifestSummaryHandler: preload timeout, will lazy-load on first request");
    }

    public void clearCache() {
        cachedJson = null;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!PluginServer.getInstance().isRunning()) {
            http.sendError(exchange, 503, "Service unavailable");
            return;
        }

        try {
            String json = cachedJson;
            if (json == null) {
                JadxDecompiler decompiler = JadxUtil.getDecompiler();
                if (decompiler == null) {
                    http.sendError(exchange, 500, "Decompiler not available");
                    return;
                }
                String xml = ManifestParser.getManifestXml(decompiler);
                if (xml == null || xml.isBlank()) {
                    http.sendError(exchange, 404, "AndroidManifest.xml not found or empty");
                    return;
                }
                json = buildSummaryJson(xml);
                cachedJson = json;
            }
            http.sendResponse(exchange, 200, json);
        } catch (Exception e) {
            logger.error("ManifestSummary failed", e);
            http.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private String buildSummaryJson(String xml) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "manifest-summary");

        String pkg = ManifestParser.extractPackageName(xml);
        result.put("package_name", pkg);

        // SDK versions
        String minSdk = extractFirst(xml, Pattern.compile("android:minSdkVersion\\s*=\\s*\"([^\"]+)\""));
        String targetSdk = extractFirst(xml, Pattern.compile("android:targetSdkVersion\\s*=\\s*\"([^\"]+)\""));
        if (!minSdk.isEmpty()) result.put("min_sdk", HttpUtil.parseInt(minSdk, -1));
        if (!targetSdk.isEmpty()) result.put("target_sdk", HttpUtil.parseInt(targetSdk, -1));

        // Application security attributes (compact)
        result.put("application", parseApplicationAttrs(xml));

        // Components summary: only name + type + exported + has_intent_filter
        List<Map<String, Object>> componentsSummary = new ArrayList<>();
        int exportedCount = 0;

        exportedCount += addComponentsSummary(componentsSummary, xml, "<activity", "activity", pkg);
        exportedCount += addComponentsSummary(componentsSummary, xml, "<service", "service", pkg);
        exportedCount += addComponentsSummary(componentsSummary, xml, "<receiver", "receiver", pkg);
        exportedCount += addComponentsSummary(componentsSummary, xml, "<provider", "provider", pkg);

        result.put("components_summary", componentsSummary);
        result.put("total_components", componentsSummary.size());
        result.put("exported_count", exportedCount);

        // Permissions (flat list)
        result.put("permissions", ManifestParser.extractUsesPermissions(xml));

        // Security findings (reuse analysis logic)
        result.put("security_findings", analyzeSecurityCompact(xml, componentsSummary));

        // Deep links: count + preview (first 5 schemes)
        List<String> deepLinkPreviews = extractDeepLinkPreviews(xml, pkg);
        result.put("deep_links_count", deepLinkPreviews.size());
        if (deepLinkPreviews.size() > 5) {
            result.put("deep_links_preview", deepLinkPreviews.subList(0, 5));
        } else {
            result.put("deep_links_preview", deepLinkPreviews);
        }

        return http.toJson(result);
    }

    private int addComponentsSummary(List<Map<String, Object>> out, String xml, String tagPrefix, String type, String pkg) {
        String[] blocks = xml.split(tagPrefix);
        int exportedCount = 0;

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int closeTag = block.indexOf(">");
            // For components with children, find the closing tag
            String tagName = tagPrefix.replace("<", "").trim();
            int closeTagIdx = block.indexOf("</" + tagName + ">");
            String componentBlock = closeTagIdx > 0 ? block.substring(0, closeTagIdx) : (closeTag > 0 ? block.substring(0, closeTag) : block);

            String name = extractAttr(componentBlock, ATTR_NAME);
            if (name.isEmpty()) continue;

            boolean hasIntentFilter = componentBlock.contains("<intent-filter");
            String exportedStr = extractAttr(componentBlock, ATTR_EXPORTED);
            boolean exported;
            if (!exportedStr.isEmpty()) {
                exported = "true".equals(exportedStr);
            } else {
                exported = hasIntentFilter;
            }

            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("name", ManifestParser.normalize(pkg, name));
            comp.put("type", type);
            comp.put("exported", exported);
            comp.put("has_intent_filter", hasIntentFilter);

            // For providers, add authorities
            if ("provider".equals(type)) {
                String authorities = extractAttr(componentBlock, Pattern.compile("android:authorities\\s*=\\s*\"([^\"]+)\""));
                if (!authorities.isEmpty()) comp.put("authorities", authorities);
            }

            out.add(comp);
            if (exported) exportedCount++;
        }
        return exportedCount;
    }

    private Map<String, Object> parseApplicationAttrs(String xml) {
        Map<String, Object> app = new LinkedHashMap<>();
        int appStart = xml.indexOf("<application");
        if (appStart < 0) return app;
        int appEnd = xml.indexOf(">", appStart);
        if (appEnd < 0) return app;
        String appTag = xml.substring(appStart, appEnd);

        app.put("debuggable", "true".equals(extractAttr(appTag, Pattern.compile("android:debuggable\\s*=\\s*\"([^\"]+)\""))));
        app.put("allowBackup", !"false".equals(extractAttr(appTag, Pattern.compile("android:allowBackup\\s*=\\s*\"([^\"]+)\""))));
        String nsc = extractAttr(appTag, Pattern.compile("android:networkSecurityConfig\\s*=\\s*\"([^\"]+)\""));
        if (!nsc.isEmpty()) app.put("networkSecurityConfig", nsc);
        app.put("usesCleartextTraffic", "true".equals(extractAttr(appTag, Pattern.compile("android:usesCleartextTraffic\\s*=\\s*\"([^\"]+)\""))));
        return app;
    }

    private List<Map<String, String>> analyzeSecurityCompact(String xml, List<Map<String, Object>> components) {
        List<Map<String, String>> findings = new ArrayList<>();

        // Check debuggable
        if (xml.contains("android:debuggable=\"true\"")) {
            findings.add(makeFinding("critical", "debuggable", "Application is debuggable"));
        }

        // Check allowBackup without BackupAgent
        if (!xml.contains("android:allowBackup=\"false\"") && !xml.contains("android:backupAgent")) {
            findings.add(makeFinding("medium", "allowBackup", "allowBackup=true without custom BackupAgent"));
        }

        // Check cleartext traffic
        if (xml.contains("android:usesCleartextTraffic=\"true\"")) {
            findings.add(makeFinding("medium", "cleartext_traffic", "Allows cleartext HTTP traffic"));
        }

        // Exported without permission
        for (Map<String, Object> comp : components) {
            if (Boolean.TRUE.equals(comp.get("exported"))) {
                String name = (String) comp.get("name");
                if (name != null && !hasPermissionInXml(xml, name)) {
                    String type = (String) comp.get("type");
                    String severity = "provider".equals(type) ? "high" : "medium";
                    findings.add(makeFinding(severity, "exported_no_permission",
                            "Exported " + type + " without permission: " + shortName(name)));
                }
            }
        }

        return findings;
    }

    private boolean hasPermissionInXml(String xml, String componentName) {
        String simpleName = componentName.contains(".") ? componentName.substring(componentName.lastIndexOf('.')) : componentName;
        int idx = xml.indexOf(simpleName);
        if (idx < 0) return false;
        String block = xml.substring(idx, Math.min(xml.length(), idx + 500));
        int closeTag = block.indexOf(">");
        if (closeTag > 0) block = block.substring(0, closeTag);
        return block.contains("android:permission");
    }

    private List<String> extractDeepLinkPreviews(String xml, String pkg) {
        List<String> previews = new ArrayList<>();
        Pattern schemePattern = Pattern.compile("<data[^>]+android:scheme\\s*=\\s*\"([^\"]+)\"");
        Pattern hostPattern = Pattern.compile("android:host\\s*=\\s*\"([^\"]+)\"");

        String[] parts = xml.split("<intent-filter");
        for (int i = 1; i < parts.length; i++) {
            String filterBlock = parts[i];
            int closeIdx = filterBlock.indexOf("</intent-filter>");
            if (closeIdx > 0) filterBlock = filterBlock.substring(0, closeIdx);

            Matcher sm = schemePattern.matcher(filterBlock);
            while (sm.find()) {
                String scheme = sm.group(1);
                // Look for host near this data element
                String dataBlock = filterBlock.substring(Math.max(0, sm.start() - 10), Math.min(filterBlock.length(), sm.end() + 200));
                Matcher hm = hostPattern.matcher(dataBlock);
                String preview = scheme + "://";
                if (hm.find()) {
                    preview += hm.group(1);
                }
                if (!previews.contains(preview)) {
                    previews.add(preview);
                }
            }
        }
        return previews;
    }

    private Map<String, String> makeFinding(String severity, String type, String description) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("severity", severity);
        f.put("type", type);
        f.put("description", description);
        return f;
    }

    private String shortName(String fullName) {
        if (fullName == null) return "";
        int lastDot = fullName.lastIndexOf('.');
        return lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    private String extractAttr(String block, Pattern pattern) {
        Matcher m = pattern.matcher(block);
        return m.find() ? m.group(1) : "";
    }

    private String extractFirst(String xml, Pattern pattern) {
        Matcher m = pattern.matcher(xml);
        return m.find() ? m.group(1) : "";
    }
}
