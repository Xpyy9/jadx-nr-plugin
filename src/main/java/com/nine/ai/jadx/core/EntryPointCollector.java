package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Layer 0: 攻击入口聚合器。
 * 在 ManifestAnalyzer 解析后收集所有 exported 组件 + deep link handler，
 * 标记为入口点供 CallGraph.isEntryPoint() 和 AnalyzeHandler.handleEntryPoints() 使用。
 *
 * 入口点类型:
 * - Exported Activity (含 deep link handler)
 * - Exported Service
 * - Exported BroadcastReceiver
 * - Exported ContentProvider
 * - Application.onCreate (app initialization)
 */
public class EntryPointCollector {
    private static final Logger LOG = LoggerFactory.getLogger(EntryPointCollector.class);

    // All entry point class names
    private final Set<String> entryPointClasses = new LinkedHashSet<>();

    // Entry point → type mapping
    private final Map<String, String> entryPointTypes = new LinkedHashMap<>();

    // Entry point → deep link schemes
    private final Map<String, List<String>> deepLinkSchemes = new LinkedHashMap<>();

    // Entry point → risk level
    private final Map<String, String> riskLevels = new LinkedHashMap<>();

    // Standard lifecycle entry methods per component type
    private static final Map<String, List<String>> LIFECYCLE_METHODS = Map.of(
            "activity", List.of("onCreate", "onNewIntent", "onResume", "onStart"),
            "service", List.of("onCreate", "onStartCommand", "onBind", "onHandleIntent"),
            "receiver", List.of("onReceive"),
            "provider", List.of("onCreate", "query", "insert", "update", "delete", "call")
    );

    // ==================== Build API ====================

    /**
     * Collect entry points from ManifestAnalyzer results.
     * Called once at Layer 0 after manifest parsing.
     */
    public void collect(ManifestAnalyzer manifest) {
        if (manifest == null || !manifest.isParsed()) return;

        List<Map<String, Object>> entryPoints = manifest.getEntryPoints("all");
        for (Map<String, Object> ep : entryPoints) {
            String name = (String) ep.get("name");
            String type = (String) ep.get("type");
            boolean hasPermission = Boolean.TRUE.equals(ep.get("protected"));

            entryPointClasses.add(name);
            entryPointTypes.put(name, type);

            // Collect deep link schemes
            @SuppressWarnings("unchecked")
            List<Map<String, String>> links = (List<Map<String, String>>) ep.get("deep_links");
            if (links != null && !links.isEmpty()) {
                List<String> schemes = new ArrayList<>();
                for (Map<String, String> link : links) {
                    String scheme = link.get("scheme");
                    if (scheme != null) schemes.add(scheme);
                }
                if (!schemes.isEmpty()) {
                    deepLinkSchemes.put(name, schemes);
                }
            }

            // Compute risk level
            String risk;
            if ("provider".equals(type) && !hasPermission) {
                risk = "critical";
            } else if (!hasPermission && deepLinkSchemes.containsKey(name)) {
                risk = "high";
            } else if (!hasPermission) {
                risk = "medium";
            } else {
                risk = "low";
            }
            riskLevels.put(name, risk);
        }

        // Also add Application class as entry point if known
        String appClass = manifest.getAppClassName();
        if (appClass != null && !appClass.isEmpty()) {
            entryPointClasses.add(appClass);
            entryPointTypes.put(appClass, "application");
            riskLevels.put(appClass, "low");
        }

        LOG.info("EntryPointCollector: {} entry points ({} with deep links)",
                entryPointClasses.size(), deepLinkSchemes.size());
    }

    /**
     * Register entry points in the call graph for isEntryPoint() queries.
     * Called after Layer 2 (CallGraph) is built.
     */
    public void registerInCallGraph(CallGraph callGraph) {
        for (String className : entryPointClasses) {
            String type = entryPointTypes.getOrDefault(className, "activity");
            List<String> lifecycleMethods = LIFECYCLE_METHODS.getOrDefault(type, List.of("onCreate"));
            for (String method : lifecycleMethods) {
                callGraph.markEntryPoint(className + "#" + method);
            }
        }
    }

    // ==================== Query API ====================

    public boolean isEntryPoint(String className) {
        return entryPointClasses.contains(className);
    }

    public Set<String> getEntryPointClasses() {
        return Collections.unmodifiableSet(entryPointClasses);
    }

    public String getComponentType(String className) {
        return entryPointTypes.get(className);
    }

    public List<String> getDeepLinkSchemes(String className) {
        return deepLinkSchemes.getOrDefault(className, Collections.emptyList());
    }

    public String getRiskLevel(String className) {
        return riskLevels.getOrDefault(className, "low");
    }

    public int getTotalEntryPoints() {
        return entryPointClasses.size();
    }

    public int getDeepLinkCount() {
        return deepLinkSchemes.size();
    }

    /**
     * Get entry point method keys (className#method) for all lifecycle methods.
     */
    public Set<String> getEntryMethodKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String className : entryPointClasses) {
            String type = entryPointTypes.getOrDefault(className, "activity");
            List<String> methods = LIFECYCLE_METHODS.getOrDefault(type, List.of("onCreate"));
            for (String m : methods) {
                keys.add(className + "#" + m);
            }
        }
        return keys;
    }

    public void clear() {
        entryPointClasses.clear();
        entryPointTypes.clear();
        deepLinkSchemes.clear();
        riskLevels.clear();
    }
}
