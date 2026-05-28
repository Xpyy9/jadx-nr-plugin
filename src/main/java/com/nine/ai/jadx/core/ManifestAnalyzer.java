package com.nine.ai.jadx.core;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.ManifestParser;
import jadx.api.JadxDecompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manifest 结构化解析器 — Layer 0 核心组件。
 * <p>
 * 在 PluginServer 启动管线中调用一次 {@link #parse(JadxDecompiler)}，
 * 后续 overview / component / entryPoints 等 action 直接读取缓存结果。
 * <p>
 * 整合了原 ManifestDetailHandler + ManifestSummaryHandler 的全部解析逻辑。
 */
public class ManifestAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(ManifestAnalyzer.class);

    // ==================== Regex patterns ====================
    private static final Pattern ATTR_NAME = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_EXPORTED = Pattern.compile("android:exported\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_AUTHORITIES = Pattern.compile("android:authorities\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_PERMISSION = Pattern.compile("android:permission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_READ_PERM = Pattern.compile("android:readPermission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_WRITE_PERM = Pattern.compile("android:writePermission\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_PROTECTION = Pattern.compile("android:protectionLevel\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_VALUE = Pattern.compile("android:value\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_RESOURCE = Pattern.compile("android:resource\\s*=\\s*\"([^\"]+)\"");

    // ==================== Cached state ====================
    private volatile boolean parsed = false;
    private String packageName = "";
    private int minSdk = -1;
    private int targetSdk = -1;
    private Map<String, Object> appAttrs = Collections.emptyMap();
    private List<Map<String, Object>> activities = Collections.emptyList();
    private List<Map<String, Object>> services = Collections.emptyList();
    private List<Map<String, Object>> receivers = Collections.emptyList();
    private List<Map<String, Object>> providers = Collections.emptyList();
    private List<String> usesPermissions = Collections.emptyList();
    private List<Map<String, String>> declaredPermissions = Collections.emptyList();
    private List<String> features = Collections.emptyList();
    private List<String> queries = Collections.emptyList();
    private List<Map<String, String>> securityFindings = Collections.emptyList();
    private List<Map<String, String>> deepLinks = Collections.emptyList();

    // Component lookup by fully-qualified name
    private Map<String, Map<String, Object>> componentIndex = Collections.emptyMap();

    /**
     * Parse the AndroidManifest.xml from the loaded APK. Called once at Layer 0.
     * Clears previous state before parsing to support retry semantics.
     */
    public void parse(JadxDecompiler decompiler) {
        // Clear previous state in case this is a retry
        clearCache();

        String xml = ManifestParser.getManifestXml(decompiler);
        if (xml == null || xml.isBlank()) {
            LOG.warn("AndroidManifest.xml not found or empty");
            parsed = true; // Mark parsed even on failure to avoid blocking
            return;
        }
        parseXml(xml);
        parsed = true;
        LOG.info("ManifestAnalyzer: parsed package={}, components={}, permissions={}, findings={}, deep_links={}",
                packageName, getTotalComponents(), usesPermissions.size(), securityFindings.size(), deepLinks.size());
    }

    private void parseXml(String xml) {
        packageName = ManifestParser.extractPackageName(xml);

        // SDK versions
        String minStr = extractFirst(xml, Pattern.compile("android:minSdkVersion\\s*=\\s*\"([^\"]+)\""));
        String targetStr = extractFirst(xml, Pattern.compile("android:targetSdkVersion\\s*=\\s*\"([^\"]+)\""));
        if (!minStr.isEmpty()) minSdk = HttpUtil.parseInt(minStr, -1);
        if (!targetStr.isEmpty()) targetSdk = HttpUtil.parseInt(targetStr, -1);

        // Application attributes
        appAttrs = parseApplicationAttrs(xml);

        // Components
        activities = parseComponents(xml, "<activity", packageName);
        services = parseComponents(xml, "<service", packageName);
        receivers = parseComponents(xml, "<receiver", packageName);
        providers = parseProviders(xml, packageName);

        // Permissions
        usesPermissions = ManifestParser.extractUsesPermissions(xml);
        declaredPermissions = extractDeclaredPermissions(xml);

        // Features & queries
        features = extractAll(xml, Pattern.compile("<uses-feature[^>]+android:name\\s*=\\s*\"([^\"]+)\""));
        queries = extractQueries(xml);

        // Build component index for fast lookup
        componentIndex = new LinkedHashMap<>();
        indexComponents(activities, "activity");
        indexComponents(services, "service");
        indexComponents(receivers, "receiver");
        indexComponents(providers, "provider");

        // Security findings (depends on components being parsed)
        securityFindings = analyzeManifestSecurity(xml);

        // Deep links
        deepLinks = extractDeepLinks();
    }

    private void indexComponents(List<Map<String, Object>> comps, String type) {
        for (Map<String, Object> comp : comps) {
            String name = (String) comp.get("name");
            if (name != null) {
                Map<String, Object> indexed = new LinkedHashMap<>(comp);
                indexed.put("type", type);
                componentIndex.put(name, indexed);
            }
        }
    }

    // ==================== Public accessors ====================

    public boolean isParsed() { return parsed; }
    public String getPackageName() { return packageName; }
    public String getAppClassName() {
        Object name = appAttrs.get("name");
        return name != null ? name.toString() : null;
    }
    public int getMinSdk() { return minSdk; }
    public int getTargetSdk() { return targetSdk; }
    public Map<String, Object> getAppAttrs() { return appAttrs; }
    public List<String> getPermissions() { return usesPermissions; }
    public List<Map<String, String>> getSecurityFindings() { return securityFindings; }

    public int getTotalComponents() {
        return activities.size() + services.size() + receivers.size() + providers.size();
    }

    public int getExportedCount() {
        int count = 0;
        for (Map<String, Object> comp : componentIndex.values()) {
            if (Boolean.TRUE.equals(comp.get("exported"))) count++;
        }
        return count;
    }

    /**
     * Get the full detail for the overview action (used by SystemHandler).
     * Returns the complete parsed manifest as a structured map.
     */
    public Map<String, Object> getFullDetail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("package_name", packageName);
        if (minSdk > 0) result.put("min_sdk", minSdk);
        if (targetSdk > 0) result.put("target_sdk", targetSdk);
        result.put("application", appAttrs);

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("activities", activities);
        components.put("services", services);
        components.put("receivers", receivers);
        components.put("providers", providers);
        result.put("components", components);

        result.put("permissions_used", usesPermissions);
        result.put("permissions_declared", declaredPermissions);
        result.put("features", features);
        result.put("queries", queries);
        result.put("security_findings", securityFindings);
        result.put("deep_links", deepLinks);

        return result;
    }

    /**
     * Get compact summary for Go agent preload (~500 tokens).
     * Used by SystemHandler.handleOverview to provide concise manifest info.
     */
    public Map<String, Object> getCompactSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("package_name", packageName);
        if (minSdk > 0) result.put("min_sdk", minSdk);
        if (targetSdk > 0) result.put("target_sdk", targetSdk);
        result.put("application", appAttrs);

        // Compact components: name + type + exported + has_intent_filter
        List<Map<String, Object>> compSummary = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : componentIndex.entrySet()) {
            Map<String, Object> comp = entry.getValue();
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", entry.getKey());
            compact.put("type", comp.get("type"));
            compact.put("exported", comp.get("exported"));
            compact.put("has_intent_filter", comp.containsKey("intent_filters"));
            compSummary.add(compact);
        }
        result.put("components_summary", compSummary);
        result.put("total_components", getTotalComponents());
        result.put("exported_count", getExportedCount());
        result.put("permissions", usesPermissions);
        result.put("security_findings", securityFindings);
        result.put("deep_links_count", deepLinks.size());
        if (deepLinks.size() > 5) {
            result.put("deep_links_preview", deepLinks.subList(0, 5));
        } else {
            result.put("deep_links_preview", deepLinks);
        }

        return result;
    }

    /**
     * Get detailed info for a single component (used by AnalyzeHandler.handleComponent).
     * Returns manifest metadata: exported, intent-filters, meta-data, permissions, deep links.
     * Returns null if component not found in manifest.
     */
    public Map<String, Object> getComponentInfo(String name) {
        Map<String, Object> comp = componentIndex.get(name);
        if (comp != null) return comp;

        // Try matching by simple name
        for (Map.Entry<String, Map<String, Object>> entry : componentIndex.entrySet()) {
            if (entry.getKey().endsWith("." + name) || entry.getKey().endsWith(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get exported components (entry points) filtered by type.
     * Used by AnalyzeHandler.handleEntryPoints.
     *
     * @param filter "all", "activity", "service", "receiver", "provider", or "deeplink"
     */
    public List<Map<String, Object>> getEntryPoints(String filter) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean filterDeeplink = "deeplink".equals(filter);

        for (Map.Entry<String, Map<String, Object>> entry : componentIndex.entrySet()) {
            Map<String, Object> comp = entry.getValue();
            if (!Boolean.TRUE.equals(comp.get("exported"))) continue;

            String type = (String) comp.get("type");

            // Type filter
            if (!"all".equals(filter) && !filterDeeplink && !filter.equals(type)) continue;

            // Deep link filter: only include components with deep link data elements
            if (filterDeeplink) {
                if (!hasDeepLink(comp)) continue;
            }

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("name", entry.getKey());
            ep.put("type", type);
            ep.put("exported", true);

            // Intent filters
            if (comp.containsKey("intent_filters")) {
                ep.put("intent_filters", comp.get("intent_filters"));
            }

            // Permission protection
            if (comp.containsKey("permission")) {
                ep.put("permission", comp.get("permission"));
                ep.put("protected", true);
            } else {
                ep.put("protected", false);
            }

            // Deep links for this component
            List<Map<String, String>> compDeepLinks = getDeepLinksForComponent(entry.getKey());
            if (!compDeepLinks.isEmpty()) {
                ep.put("deep_links", compDeepLinks);
            }

            // Risk assessment
            boolean isProtected = comp.containsKey("permission");
            String risk;
            if ("provider".equals(type) && !isProtected) {
                risk = "high";
            } else if (!isProtected) {
                risk = "medium";
            } else {
                risk = "low";
            }
            ep.put("risk", risk);

            result.add(ep);
        }
        return result;
    }

    /**
     * Get all exported component names.
     */
    public List<String> getExportedComponentNames() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : componentIndex.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue().get("exported"))) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public void clearCache() {
        parsed = false;
        packageName = "";
        minSdk = targetSdk = -1;
        appAttrs = Collections.emptyMap();
        activities = services = receivers = providers = Collections.emptyList();
        usesPermissions = Collections.emptyList();
        declaredPermissions = Collections.emptyList();
        features = queries = Collections.emptyList();
        securityFindings = Collections.emptyList();
        deepLinks = Collections.emptyList();
        componentIndex = Collections.emptyMap();
    }

    // ==================== Application attributes ====================

    private Map<String, Object> parseApplicationAttrs(String xml) {
        Map<String, Object> app = new LinkedHashMap<>();
        int appStart = xml.indexOf("<application");
        if (appStart < 0) return app;
        int appEnd = xml.indexOf(">", appStart);
        if (appEnd < 0) return app;
        String appTag = xml.substring(appStart, appEnd);

        app.put("name", extractAttr(appTag, ATTR_NAME));
        app.put("debuggable", "true".equals(extractAttr(appTag,
                Pattern.compile("android:debuggable\\s*=\\s*\"([^\"]+)\""))));
        app.put("allowBackup", !"false".equals(extractAttr(appTag,
                Pattern.compile("android:allowBackup\\s*=\\s*\"([^\"]+)\""))));
        String nsc = extractAttr(appTag, Pattern.compile("android:networkSecurityConfig\\s*=\\s*\"([^\"]+)\""));
        if (!nsc.isEmpty()) app.put("networkSecurityConfig", nsc);
        app.put("usesCleartextTraffic", "true".equals(extractAttr(appTag,
                Pattern.compile("android:usesCleartextTraffic\\s*=\\s*\"([^\"]+)\""))));

        return app;
    }

    // ==================== Component parsing ====================

    private List<Map<String, Object>> parseComponents(String xml, String tagPrefix, String pkg) {
        List<Map<String, Object>> result = new ArrayList<>();
        String[] blocks = xml.split(tagPrefix);

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String componentBlock = extractComponentBlock(block, tagPrefix);

            Map<String, Object> comp = new LinkedHashMap<>();
            String name = extractAttr(componentBlock, ATTR_NAME);
            if (name.isEmpty()) continue;
            comp.put("name", ManifestParser.normalize(pkg, name));

            // Exported
            String exported = extractAttr(componentBlock, ATTR_EXPORTED);
            boolean hasIntentFilter = componentBlock.contains("<intent-filter");
            if (!exported.isEmpty()) {
                comp.put("exported", "true".equals(exported));
            } else {
                comp.put("exported", hasIntentFilter);
            }

            // Permission
            String permission = extractAttr(componentBlock, ATTR_PERMISSION);
            if (!permission.isEmpty()) comp.put("permission", permission);

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
            comp.put("name", ManifestParser.normalize(pkg, name));

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
            filter.put("actions", extractAll(filterBlock,
                    Pattern.compile("<action[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));
            filter.put("categories", extractAll(filterBlock,
                    Pattern.compile("<category[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));

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

    // ==================== Queries ====================

    private List<String> extractQueries(String xml) {
        List<String> result = new ArrayList<>();
        int qStart = xml.indexOf("<queries");
        if (qStart < 0) return result;
        int qEnd = xml.indexOf("</queries>");
        if (qEnd < 0) return result;
        String queriesBlock = xml.substring(qStart, qEnd);

        result.addAll(extractAll(queriesBlock,
                Pattern.compile("<package[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));
        result.addAll(extractAll(queriesBlock,
                Pattern.compile("<action[^>]+android:name\\s*=\\s*\"([^\"]+)\"")));

        return result;
    }

    // ==================== Security Analysis ====================

    private List<Map<String, String>> analyzeManifestSecurity(String xml) {
        List<Map<String, String>> findings = new ArrayList<>();

        // Debuggable
        if (Boolean.TRUE.equals(appAttrs.get("debuggable"))) {
            findings.add(makeFinding("critical", "debuggable",
                    "Application is debuggable (android:debuggable=true)"));
        }

        // allowBackup without BackupAgent
        if (Boolean.TRUE.equals(appAttrs.get("allowBackup")) && !xml.contains("android:backupAgent")) {
            findings.add(makeFinding("medium", "allowBackup",
                    "Application allows backup without custom BackupAgent"));
        }

        // Cleartext traffic
        if (Boolean.TRUE.equals(appAttrs.get("usesCleartextTraffic"))) {
            findings.add(makeFinding("medium", "cleartext_traffic",
                    "Application allows cleartext HTTP traffic"));
        }

        // Exported components without permission
        for (Map.Entry<String, Map<String, Object>> entry : componentIndex.entrySet()) {
            Map<String, Object> comp = entry.getValue();
            if (!Boolean.TRUE.equals(comp.get("exported"))) continue;

            String type = (String) comp.get("type");
            boolean hasPerm = comp.containsKey("permission");

            if ("provider".equals(type)) {
                boolean hasAnyPerm = hasPerm || comp.containsKey("readPermission") || comp.containsKey("writePermission");
                if (!hasAnyPerm) {
                    findings.add(makeFinding("high", "exported_provider_no_permission",
                            "Exported ContentProvider without read/write permission: " + entry.getKey()));
                }
            } else if (!hasPerm) {
                findings.add(makeFinding("medium", "exported_no_permission",
                        "Exported " + type + " without permission: " + entry.getKey()));
            }
        }

        return findings;
    }

    private Map<String, String> makeFinding(String severity, String type, String description) {
        Map<String, String> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("type", type);
        finding.put("description", description);
        return finding;
    }

    // ==================== Deep Links ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractDeepLinks() {
        List<Map<String, String>> result = new ArrayList<>();

        for (Map<String, Object> activity : activities) {
            List<Map<String, Object>> filters = (List<Map<String, Object>>) activity.get("intent_filters");
            if (filters == null) continue;

            String compName = (String) activity.get("name");
            boolean exported = Boolean.TRUE.equals(activity.get("exported"));

            for (Map<String, Object> filter : filters) {
                List<Map<String, String>> dataEntries = (List<Map<String, String>>) filter.get("data");
                if (dataEntries == null) continue;

                for (Map<String, String> data : dataEntries) {
                    if (data.containsKey("scheme")) {
                        Map<String, String> link = new LinkedHashMap<>();
                        link.put("component", compName != null ? compName : "");
                        link.put("scheme", data.getOrDefault("scheme", ""));
                        if (data.containsKey("host")) link.put("host", data.get("host"));
                        if (data.containsKey("path")) link.put("path", data.get("path"));
                        if (data.containsKey("pathPrefix")) link.put("path_prefix", data.get("pathPrefix"));
                        if (data.containsKey("pathPattern")) link.put("path_pattern", data.get("pathPattern"));
                        link.put("exported", String.valueOf(exported));
                        result.add(link);
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean hasDeepLink(Map<String, Object> comp) {
        List<Map<String, Object>> filters = (List<Map<String, Object>>) comp.get("intent_filters");
        if (filters == null) return false;
        for (Map<String, Object> filter : filters) {
            List<Map<String, String>> dataEntries = (List<Map<String, String>>) filter.get("data");
            if (dataEntries != null) {
                for (Map<String, String> data : dataEntries) {
                    if (data.containsKey("scheme")) return true;
                }
            }
        }
        return false;
    }

    private List<Map<String, String>> getDeepLinksForComponent(String compName) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> link : deepLinks) {
            if (compName.equals(link.get("component"))) {
                result.add(link);
            }
        }
        return result;
    }

    // ==================== Helpers ====================

    private String extractComponentBlock(String blockAfterTag, String tagPrefix) {
        String tagName = tagPrefix.replace("<", "").trim();
        int closeTagIdx = blockAfterTag.indexOf("</" + tagName + ">");
        if (closeTagIdx > 0) {
            return blockAfterTag.substring(0, closeTagIdx);
        }
        int selfClose = blockAfterTag.indexOf("/>");
        if (selfClose > 0) {
            return blockAfterTag.substring(0, selfClose);
        }
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
}
