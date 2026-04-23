package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manifest 结构化解析：一次调用返回所有安全相关信息，替代多次 getResourceFile 分页读取。
 */
public class ManifestDetailHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(ManifestDetailHandler.class);
    private final HttpUtil http = HttpUtil.getInstance();

    private static final Pattern ATTR_NAME = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_EXPORTED = Pattern.compile("android:exported\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_AUTHORITIES = Pattern.compile("android:authorities\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_PERMISSION = Pattern.compile("android:permission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_READ_PERM = Pattern.compile("android:readPermission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_WRITE_PERM = Pattern.compile("android:writePermission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_PROTECTION = Pattern.compile("android:protectionLevel\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_VALUE = Pattern.compile("android:value\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_RESOURCE = Pattern.compile("android:resource\\s*=\\s*\"([^\"]+)\"");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!PluginServer.getInstance().isRunning()) {
            http.sendError(exchange, 503, "Service unavailable");
            return;
        }

        try {
            JadxDecompiler decompiler = JadxUtil.getDecompiler();
            if (decompiler == null) {
                http.sendError(exchange, 500, "Decompiler not available");
                return;
            }

            String xml = getManifestXml(decompiler);
            if (xml == null || xml.isBlank()) {
                http.sendError(exchange, 404, "AndroidManifest.xml not found or empty");
                return;
            }

            Map<String, Object> result = parseManifest(xml);
            http.sendResponse(exchange, 200, http.toJson(result));

        } catch (Exception e) {
            logger.error("ManifestDetail failed", e);
            http.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private String getManifestXml(JadxDecompiler decompiler) {
        for (ResourceFile res : decompiler.getResources()) {
            if ("AndroidManifest.xml".equals(res.getOriginalName())) {
                return JadxUtil.getResourceContent(res);
            }
        }
        return null;
    }

    private Map<String, Object> parseManifest(String xml) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "manifest-detail");

        String pkg = extractFirst(xml, Pattern.compile("<manifest[^>]+package\\s*=\\s*\"([^\"]+)\""));
        result.put("package_name", pkg);

        // SDK versions
        String minSdk = extractFirst(xml, Pattern.compile("android:minSdkVersion\\s*=\\s*\"([^\"]+)\""));
        String targetSdk = extractFirst(xml, Pattern.compile("android:targetSdkVersion\\s*=\\s*\"([^\"]+)\""));
        if (!minSdk.isEmpty()) result.put("min_sdk", HttpUtil.parseInt(minSdk, -1));
        if (!targetSdk.isEmpty()) result.put("target_sdk", HttpUtil.parseInt(targetSdk, -1));

        // Application attributes
        result.put("application", parseApplicationAttrs(xml));

        // Components
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("activities", parseComponents(xml, "<activity", pkg));
        components.put("services", parseComponents(xml, "<service", pkg));
        components.put("receivers", parseComponents(xml, "<receiver", pkg));
        components.put("providers", parseProviders(xml, pkg));
        result.put("components", components);

        // Permissions
        result.put("permissions_used", extractUsesPermissions(xml));
        result.put("permissions_declared", extractDeclaredPermissions(xml));

        // Features
        result.put("features", extractFeatures(xml));

        // Queries
        result.put("queries", extractQueries(xml));

        // Security findings from manifest analysis
        result.put("security_findings", analyzeManifestSecurity(xml, components, result));

        // Deep links aggregation
        result.put("deep_links", extractDeepLinks(components));

        return result;
    }

    // ==================== Application Attributes ====================

    private Map<String, Object> parseApplicationAttrs(String xml) {
        Map<String, Object> app = new LinkedHashMap<>();

        // Extract the <application ...> tag (up to first >)
        int appStart = xml.indexOf("<application");
        if (appStart < 0) return app;
        int appEnd = xml.indexOf(">", appStart);
        if (appEnd < 0) return app;
        String appTag = xml.substring(appStart, appEnd);

        app.put("name", extractAttr(appTag, ATTR_NAME));
        app.put("debuggable", "true".equals(extractAttr(appTag, Pattern.compile("android:debuggable\\s*=\\s*\"([^\"]+)\""))));
        app.put("allowBackup", !"false".equals(extractAttr(appTag, Pattern.compile("android:allowBackup\\s*=\\s*\"([^\"]+)\""))));
        String nsc = extractAttr(appTag, Pattern.compile("android:networkSecurityConfig\\s*=\\s*\"([^\"]+)\""));
        if (!nsc.isEmpty()) app.put("networkSecurityConfig", nsc);
        app.put("usesCleartextTraffic", "true".equals(extractAttr(appTag, Pattern.compile("android:usesCleartextTraffic\\s*=\\s*\"([^\"]+)\""))));

        return app;
    }

    // ==================== Components ====================

    private List<Map<String, Object>> parseComponents(String xml, String tagPrefix, String pkg) {
        List<Map<String, Object>> result = new ArrayList<>();
        String[] blocks = xml.split(tagPrefix);

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            // Find the closing of this component (either self-closing or paired close)
            String componentBlock = extractComponentBlock(block, tagPrefix);

            Map<String, Object> comp = new LinkedHashMap<>();

            // Name
            String name = extractAttr(componentBlock, ATTR_NAME);
            if (name.isEmpty()) continue;
            comp.put("name", normalize(pkg, name));

            // Exported
            String exported = extractAttr(componentBlock, ATTR_EXPORTED);
            boolean hasIntentFilter = componentBlock.contains("<intent-filter");
            if (!exported.isEmpty()) {
                comp.put("exported", "true".equals(exported));
            } else {
                // Default: exported=true if has intent-filter
                comp.put("exported", hasIntentFilter);
            }

            // Intent filters
            if (hasIntentFilter) {
                comp.put("intent_filters", parseIntentFilters(componentBlock));
            }

            // Meta-data
            Map<String, String> metaData = parseMetaData(componentBlock);
            if (!metaData.isEmpty()) {
                comp.put("meta_data", metaData);
            }

            result.add(comp);
        }
        return result;
    }

    private List<Map<String, Object>> parseProviders(String xml, String pkg) {
        List<Map<String, Object>> result = new ArrayList<>();
        String[] blocks = xml.split("<provider");

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String componentBlock = extractComponentBlock(block, "<provider");

            Map<String, Object> comp = new LinkedHashMap<>();

            String name = extractAttr(componentBlock, ATTR_NAME);
            if (name.isEmpty()) continue;
            comp.put("name", normalize(pkg, name));

            String exported = extractAttr(componentBlock, ATTR_EXPORTED);
            boolean hasIntentFilter = componentBlock.contains("<intent-filter");
            if (!exported.isEmpty()) {
                comp.put("exported", "true".equals(exported));
            } else {
                comp.put("exported", hasIntentFilter);
            }

            // Provider-specific attributes
            String authorities = extractAttr(componentBlock, ATTR_AUTHORITIES);
            if (!authorities.isEmpty()) comp.put("authorities", authorities);
            String permission = extractAttr(componentBlock, ATTR_PERMISSION);
            if (!permission.isEmpty()) comp.put("permission", permission);
            String readPerm = extractAttr(componentBlock, ATTR_READ_PERM);
            if (!readPerm.isEmpty()) comp.put("readPermission", readPerm);
            String writePerm = extractAttr(componentBlock, ATTR_WRITE_PERM);
            if (!writePerm.isEmpty()) comp.put("writePermission", writePerm);

            // Intent filters & meta-data
            if (hasIntentFilter) {
                comp.put("intent_filters", parseIntentFilters(componentBlock));
            }
            Map<String, String> metaData = parseMetaData(componentBlock);
            if (!metaData.isEmpty()) {
                comp.put("meta_data", metaData);
            }

            result.add(comp);
        }
        return result;
    }

    // ==================== Intent Filters ====================

    private List<Map<String, Object>> parseIntentFilters(String block) {
        List<Map<String, Object>> filters = new ArrayList<>();
        String[] parts = block.split("<intent-filter");

        for (int i = 1; i < parts.length; i++) {
            String filterBlock = parts[i];
            int closeIdx = filterBlock.indexOf("</intent-filter>");
            if (closeIdx > 0) filterBlock = filterBlock.substring(0, closeIdx);

            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("actions", extractAll(filterBlock, Pattern.compile("<action[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));
            filter.put("categories", extractAll(filterBlock, Pattern.compile("<category[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));

            // Data elements (scheme, host, path, etc.)
            List<Map<String, String>> dataEntries = parseDataElements(filterBlock);
            if (!dataEntries.isEmpty()) {
                filter.put("data", dataEntries);
            }

            filters.add(filter);
        }
        return filters;
    }

    private List<Map<String, String>> parseDataElements(String filterBlock) {
        List<Map<String, String>> dataList = new ArrayList<>();
        String[] dataParts = filterBlock.split("<data\\b");

        for (int i = 1; i < dataParts.length; i++) {
            String dataPart = dataParts[i];
            int closeIdx = dataPart.indexOf(">");
            if (closeIdx > 0) dataPart = dataPart.substring(0, closeIdx);

            Map<String, String> data = new LinkedHashMap<>();
            addIfPresent(data, "scheme", dataPart, "android:scheme");
            addIfPresent(data, "host", dataPart, "android:host");
            addIfPresent(data, "path", dataPart, "android:path");
            addIfPresent(data, "pathPrefix", dataPart, "android:pathPrefix");
            addIfPresent(data, "pathPattern", dataPart, "android:pathPattern");
            addIfPresent(data, "mimeType", dataPart, "android:mimeType");

            if (!data.isEmpty()) {
                dataList.add(data);
            }
        }
        return dataList;
    }

    private void addIfPresent(Map<String, String> map, String key, String block, String attrName) {
        Pattern p = Pattern.compile(attrName + "\\s*=\\s*\"([^\"]+)\"");
        String val = extractAttr(block, p);
        if (!val.isEmpty()) map.put(key, val);
    }

    // ==================== Meta-data ====================

    private Map<String, String> parseMetaData(String block) {
        Map<String, String> metaData = new LinkedHashMap<>();
        String[] parts = block.split("<meta-data");

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int closeIdx = part.indexOf(">");
            if (closeIdx > 0) part = part.substring(0, closeIdx);

            String name = extractAttr(part, ATTR_NAME);
            if (name.isEmpty()) continue;

            String value = extractAttr(part, ATTR_VALUE);
            if (value.isEmpty()) {
                value = extractAttr(part, ATTR_RESOURCE);
            }
            metaData.put(name, value);
        }
        return metaData;
    }

    // ==================== Permissions ====================

    private List<String> extractUsesPermissions(String xml) {
        return extractAll(xml, Pattern.compile("<uses-permission[^>]+android:name\\s*=\\s*\"([^\"]+)\""));
    }

    private List<Map<String, String>> extractDeclaredPermissions(String xml) {
        List<Map<String, String>> perms = new ArrayList<>();
        String[] blocks = xml.split("<permission\\b");

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int closeIdx = block.indexOf(">");
            if (closeIdx > 0) block = block.substring(0, closeIdx);

            String name = extractAttr(block, ATTR_NAME);
            if (name.isEmpty()) continue;

            Map<String, String> perm = new LinkedHashMap<>();
            perm.put("name", name);
            String protection = extractAttr(block, ATTR_PROTECTION);
            if (!protection.isEmpty()) perm.put("protectionLevel", protection);

            perms.add(perm);
        }
        return perms;
    }

    // ==================== Features & Queries ====================

    private List<String> extractFeatures(String xml) {
        return extractAll(xml, Pattern.compile("<uses-feature[^>]+android:name\\s*=\\s*\"([^\"]+)\""));
    }

    private List<String> extractQueries(String xml) {
        List<String> queries = new ArrayList<>();

        // <queries><package android:name="..."/></queries>
        int qStart = xml.indexOf("<queries");
        if (qStart < 0) return queries;
        int qEnd = xml.indexOf("</queries>");
        if (qEnd < 0) return queries;
        String queriesBlock = xml.substring(qStart, qEnd);

        queries.addAll(extractAll(queriesBlock, Pattern.compile("<package[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));

        // Also capture <intent> inside queries if present
        List<String> intentActions = extractAll(queriesBlock, Pattern.compile("<action[^>]+android:name\\s*=\\s*\"([^\"]+)\""));
        queries.addAll(intentActions);

        return queries;
    }

    // ==================== Security Analysis ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> analyzeManifestSecurity(String xml, Map<String, Object> components, Map<String, Object> result) {
        List<Map<String, String>> findings = new ArrayList<>();

        // Check debuggable
        Map<String, Object> app = (Map<String, Object>) result.get("application");
        if (app != null && Boolean.TRUE.equals(app.get("debuggable"))) {
            findings.add(makeSecurityFinding("critical", "debuggable",
                    "Application is debuggable (android:debuggable=true)"));
        }

        // Check allowBackup without custom BackupAgent
        if (app != null && Boolean.TRUE.equals(app.get("allowBackup"))) {
            String appName = app.get("name") != null ? app.get("name").toString() : "";
            if (!xml.contains("android:backupAgent")) {
                findings.add(makeSecurityFinding("medium", "allowBackup",
                        "Application allows backup without custom BackupAgent"));
            }
        }

        // Check exported components without permission
        for (String type : new String[]{"activities", "services", "receivers"}) {
            List<Map<String, Object>> comps = (List<Map<String, Object>>) components.get(type);
            if (comps == null) continue;
            for (Map<String, Object> comp : comps) {
                if (Boolean.TRUE.equals(comp.get("exported"))) {
                    String name = comp.get("name") != null ? comp.get("name").toString() : "";
                    // Check if component block in XML has a permission attribute
                    if (!hasPermissionProtection(xml, name)) {
                        findings.add(makeSecurityFinding("medium", "exported_no_permission",
                                "Exported " + type.substring(0, type.length()-1) + " without permission: " + name));
                    }
                }
            }
        }

        // Check exported ContentProviders without read/write permission
        List<Map<String, Object>> providers = (List<Map<String, Object>>) components.get("providers");
        if (providers != null) {
            for (Map<String, Object> p : providers) {
                if (Boolean.TRUE.equals(p.get("exported"))) {
                    boolean hasPerm = p.containsKey("permission") || p.containsKey("readPermission") || p.containsKey("writePermission");
                    if (!hasPerm) {
                        String name = p.get("name") != null ? p.get("name").toString() : "";
                        findings.add(makeSecurityFinding("high", "exported_provider_no_permission",
                                "Exported ContentProvider without read/write permission: " + name));
                    }
                }
            }
        }

        // Check cleartext traffic
        if (app != null && Boolean.TRUE.equals(app.get("usesCleartextTraffic"))) {
            findings.add(makeSecurityFinding("medium", "cleartext_traffic",
                    "Application allows cleartext HTTP traffic"));
        }

        return findings;
    }

    private boolean hasPermissionProtection(String xml, String componentName) {
        // Simple heuristic: find the component block and check for android:permission
        String simpleName = componentName.contains(".") ? componentName.substring(componentName.lastIndexOf('.')) : componentName;
        int idx = xml.indexOf(simpleName);
        if (idx < 0) return false;
        // Look at the block around this component (up to 500 chars after)
        String block = xml.substring(idx, Math.min(xml.length(), idx + 500));
        int closeTag = block.indexOf(">");
        if (closeTag > 0) block = block.substring(0, closeTag);
        return block.contains("android:permission");
    }

    private Map<String, String> makeSecurityFinding(String severity, String type, String description) {
        Map<String, String> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("type", type);
        finding.put("description", description);
        return finding;
    }

    // ==================== Deep Links ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractDeepLinks(Map<String, Object> components) {
        List<Map<String, String>> deepLinks = new ArrayList<>();

        List<Map<String, Object>> activities = (List<Map<String, Object>>) components.get("activities");
        if (activities == null) return deepLinks;

        for (Map<String, Object> activity : activities) {
            List<Map<String, Object>> filters = (List<Map<String, Object>>) activity.get("intent_filters");
            if (filters == null) continue;

            String compName = activity.get("name") != null ? activity.get("name").toString() : "";
            boolean exported = Boolean.TRUE.equals(activity.get("exported"));

            for (Map<String, Object> filter : filters) {
                List<Map<String, String>> dataEntries = (List<Map<String, String>>) filter.get("data");
                if (dataEntries == null) continue;

                for (Map<String, String> data : dataEntries) {
                    if (data.containsKey("scheme")) {
                        Map<String, String> link = new LinkedHashMap<>();
                        link.put("component", compName);
                        link.put("scheme", data.getOrDefault("scheme", ""));
                        if (data.containsKey("host")) link.put("host", data.get("host"));
                        if (data.containsKey("path")) link.put("path", data.get("path"));
                        if (data.containsKey("pathPrefix")) link.put("path_prefix", data.get("pathPrefix"));
                        if (data.containsKey("pathPattern")) link.put("path_pattern", data.get("pathPattern"));
                        link.put("exported", String.valueOf(exported));
                        deepLinks.add(link);
                    }
                }
            }
        }
        return deepLinks;
    }

    // ==================== Helpers ====================

    /**
     * Extract the component block content. For self-closing tags (/>) takes up to that point;
     * for paired tags, takes up to the corresponding close tag.
     */
    private String extractComponentBlock(String blockAfterTag, String tagPrefix) {
        // Determine the tag name (e.g. "activity" from "<activity")
        String tagName = tagPrefix.replace("<", "").trim();
        int closeTagIdx = blockAfterTag.indexOf("</" + tagName + ">");
        if (closeTagIdx > 0) {
            return blockAfterTag.substring(0, closeTagIdx);
        }
        // Self-closing: use everything up to first />
        int selfClose = blockAfterTag.indexOf("/>");
        if (selfClose > 0) {
            return blockAfterTag.substring(0, selfClose);
        }
        // Fallback: use everything up to next tag at same level
        return blockAfterTag;
    }

    private String extractAttr(String block, Pattern pattern) {
        Matcher m = pattern.matcher(block);
        return m.find() ? m.group(1) : "";
    }

    private String extractFirst(String xml, Pattern pattern) {
        Matcher m = pattern.matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private List<String> extractAll(String block, Pattern pattern) {
        List<String> result = new ArrayList<>();
        Matcher m = pattern.matcher(block);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private String normalize(String pkg, String cls) {
        if (cls.startsWith(".")) return pkg + cls;
        if (!cls.contains(".")) return pkg + "." + cls;
        return cls;
    }
}
