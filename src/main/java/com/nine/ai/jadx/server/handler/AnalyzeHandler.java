package com.nine.ai.jadx.server.handler;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.core.CallGraph;
import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.core.ManifestAnalyzer;
import com.nine.ai.jadx.core.SSATaintAnalyzer;
import com.nine.ai.jadx.core.SecurityAnnotator;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * /analyze 路由处理器
 * Actions: component, callChain, dataFlow, entryPoints, attackSurface, resolveDI
 */
public class AnalyzeHandler extends BaseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyzeHandler.class);

    private final CodeIndexManager codeIndex;
    private final ManifestAnalyzer manifestAnalyzer;

    public AnalyzeHandler(AnalysisLayers layers, CodeIndexManager codeIndex, ManifestAnalyzer manifestAnalyzer) {
        super(layers);
        this.codeIndex = codeIndex;
        this.manifestAnalyzer = manifestAnalyzer;
    }

    @Override
    protected int requiredLayer(String action) {
        switch (action) {
            case "entryPoints": return 0;        // Only manifest needed
            case "component": return 0;          // Manifest + code
            case "callChain": return 2;          // Needs CallGraph
            case "dataFlow": return 2;           // Needs CallGraph + (Layer 4 on demand)
            case "attackSurface": return 2;      // Needs L0-L2, L3 optional
            case "resolveDI": return 2;          // Needs DI resolver
            default: return 0;
        }
    }

    @Override
    protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
        switch (action) {
            case "component":
                handleComponent(exchange, params);
                break;
            case "callChain":
                handleCallChain(exchange, params);
                break;
            case "dataFlow":
                handleDataFlow(exchange, params);
                break;
            case "entryPoints":
                handleEntryPoints(exchange, params);
                break;
            case "attackSurface":
                handleAttackSurface(exchange, params);
                break;
            case "resolveDI":
                handleResolveDI(exchange, params);
                break;
            default:
                http.sendError(exchange, 400,
                        "Unknown action for /analyze: '" + action + "'. Valid: component, callChain, dataFlow, entryPoints, attackSurface, resolveDI");
        }
    }

    private void handleComponent(HttpExchange exchange, Map<String, String> params) throws IOException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            http.sendError(exchange, 400, "component requires parameter 'name'");
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component_name", name);

        // 1. Manifest metadata from ManifestAnalyzer
        Map<String, Object> manifestInfo = manifestAnalyzer.getComponentInfo(name);
        if (manifestInfo != null) {
            result.put("type", manifestInfo.get("type"));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("exported", manifestInfo.get("exported"));
            boolean hasPermission = manifestInfo.containsKey("permission") && manifestInfo.get("permission") != null;
            manifest.put("has_permission", hasPermission);
            if (manifestInfo.containsKey("intent_filters")) {
                manifest.put("intent_filters", manifestInfo.get("intent_filters"));
            }
            if (manifestInfo.containsKey("meta_data")) {
                manifest.put("meta_data", manifestInfo.get("meta_data"));
            }
            if (hasPermission) {
                manifest.put("permission", manifestInfo.get("permission"));
            }
            if (manifestInfo.containsKey("authorities")) {
                manifest.put("authorities", manifestInfo.get("authorities"));
            }
            // Generate security_note
            boolean exported = Boolean.TRUE.equals(manifestInfo.get("exported"));
            if (exported && !hasPermission) {
                StringBuilder note = new StringBuilder("exported 且无 permission 保护");
                if (manifestInfo.containsKey("deep_links")) {
                    note.append("，接受 deep link 输入");
                }
                manifest.put("security_note", note.toString());
            }
            result.put("manifest", manifest);
        } else {
            result.put("manifest", null);
            result.put("manifest_note", "Component not found in AndroidManifest.xml");
        }

        // 2. Class structure + decompiled code
        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler != null) {
            Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
            JavaClass cls = CodeUtil.findClassDeeply(cache, name, decompiler);
            if (cls != null) {
                Map<String, Object> structure = new LinkedHashMap<>();

                // Super class
                try {
                    var superType = cls.getClassNode().getSuperClass();
                    structure.put("super_class", superType != null ? superType.getObject().replace('/', '.') : "java.lang.Object");
                } catch (Exception e) {
                    structure.put("super_class", "java.lang.Object");
                }

                // Interfaces
                try {
                    List<String> interfaces = new ArrayList<>();
                    for (var iface : cls.getClassNode().getInterfaces()) {
                        interfaces.add(iface.getObject().replace('/', '.'));
                    }
                    structure.put("implements", interfaces);
                } catch (Exception e) {
                    structure.put("implements", Collections.emptyList());
                }

                // Methods
                List<Map<String, Object>> methods = new ArrayList<>();
                for (JavaMethod m : cls.getMethods()) {
                    Map<String, Object> mInfo = new LinkedHashMap<>();
                    mInfo.put("name", m.getName());
                    try {
                        mInfo.put("signature", m.getMethodNode().getMethodInfo().getShortId());
                    } catch (Exception e) {
                        mInfo.put("signature", m.getName());
                    }

                    // Security tags (Layer 2 + Layer 3 rules)
                    if (layers.isReady(2)) {
                        String methodKey = cls.getFullName() + "#" + m.getName();
                        var ruleEngineRef = layers.isReady(3) ? codeIndex.getRuleEngine() : null;
                        Map<String, Object> tags = codeIndex.getSecurityAnnotator().getTagAsMap(methodKey, ruleEngineRef);
                        if (tags != null && !tags.isEmpty()) {
                            mInfo.put("security_tags", tags);
                        }
                    }
                    methods.add(mInfo);
                }
                structure.put("methods", methods);

                // Fields
                List<Map<String, Object>> fields = new ArrayList<>();
                for (var f : cls.getFields()) {
                    Map<String, Object> fInfo = new LinkedHashMap<>();
                    fInfo.put("name", f.getName());
                    fInfo.put("type", f.getType());
                    fields.add(fInfo);
                }
                structure.put("fields", fields);

                result.put("structure", structure);

                // Decompiled code
                String code = cls.getCode();
                result.put("code", code != null ? code : "// Code not available");

                // Class-level security summary (Layer 2 + Layer 3) with key_finding
                if (layers.isReady(2)) {
                    var ruleEngineRef = layers.isReady(3) ? codeIndex.getRuleEngine() : null;
                    Map<String, Object> secSummary = codeIndex.getSecurityAnnotator().getClassSummaryAsMap(cls.getFullName(), ruleEngineRef);
                    if (secSummary != null) {
                        // Generate key_finding
                        secSummary.put("key_finding", generateKeyFinding(cls.getFullName(), manifestInfo));
                        result.put("class_security_summary", secSummary);
                    }
                }
            } else {
                result.put("structure", null);
                result.put("code", "// Class not found in decompiled output: " + name);
            }
        }

        http.sendResponse(exchange, 200, http.toJson(result));
    }

    /**
     * Generate a key finding string for the component security summary.
     */
    private String generateKeyFinding(String className, Map<String, Object> manifestInfo) {
        var secAnnotator = codeIndex.getSecurityAnnotator();
        var summary = secAnnotator.getClassSummary(className);
        if (summary == null) return null;

        boolean exported = manifestInfo != null && Boolean.TRUE.equals(manifestInfo.get("exported"));
        boolean hasPermission = manifestInfo != null && manifestInfo.containsKey("permission");

        // Check for dangerous source→sink combos
        if (summary.sourceCategories.contains("deeplink") && summary.sinkCategories.contains("webview")) {
            return "Deep link 输入直接流入 WebView.loadUrl(), 无过滤";
        }
        if (summary.sourceCategories.contains("intent") && summary.sinkCategories.contains("sql")) {
            return "Intent 输入流入 SQL 查询, 可能存在 SQL 注入";
        }
        if (exported && !hasPermission && summary.totalSinks > 0) {
            return "exported 组件无权限保护, 包含 " + summary.totalSinks + " 个 sink 调用";
        }
        if (summary.sinkCategories.contains("crypto")) {
            return "包含加密操作, 需要检查算法强度和密钥管理";
        }
        if (summary.totalSinks > 3) {
            return "包含 " + summary.totalSinks + " 个安全敏感 API 调用";
        }
        return null;
    }

    private void handleCallChain(HttpExchange exchange, Map<String, String> params) throws IOException {
        String className = params.get("class");
        String methodName = params.get("method");
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            http.sendError(exchange, 400, "callChain requires parameters 'class' and 'method'");
            return;
        }

        String direction = params.getOrDefault("direction", "up");
        // Normalize direction: "callers" → "up" for spec compatibility
        if ("callers".equalsIgnoreCase(direction)) direction = "up";
        int depth = HttpUtil.parseInt(params.get("depth"), 3);
        depth = Math.min(depth, 6); // Cap at 6

        String methodKey = className + "#" + methodName;
        var callGraph = codeIndex.getCallGraph();
        var secAnnotator = codeIndex.getSecurityAnnotator();

        // Use tree-based trace
        CallGraph.CallChainResult chainResult = callGraph.traceCallChainTree(
                methodKey, direction, depth, secAnnotator);

        // Also build flat-layer format for Go extractor compatibility
        String flatDirection = "up".equals(direction) ? "callers" : "callees";
        List<List<String>> flatLayers = callGraph.traceCallChain(methodKey, flatDirection, depth);
        List<Map<String, Object>> chainLayers = new ArrayList<>();
        for (int i = 0; i < flatLayers.size(); i++) {
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("depth", i + 1);
            List<Map<String, String>> methods = new ArrayList<>();
            for (String mk : flatLayers.get(i)) {
                Map<String, String> m = new LinkedHashMap<>();
                int h = mk.indexOf('#');
                m.put("class", h > 0 ? mk.substring(0, h) : mk);
                m.put("method", h > 0 ? mk.substring(h + 1) : "");
                methods.add(m);
            }
            layer.put("methods", methods);
            chainLayers.add(layer);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", methodKey);
        result.put("direction", direction);
        result.put("depth", depth);
        result.put("chain", chainLayers); // flat-layer format for Go extractor
        result.put("chain_tree", chainResult.tree); // nested tree for LLM
        result.put("paths_to_entry", chainResult.pathsToEntry);
        result.put("total_paths", chainResult.totalPaths);
        result.put("total_nodes", chainResult.totalNodes);
        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleDataFlow(HttpExchange exchange, Map<String, String> params) throws IOException {
        String className = params.get("class");
        String methodName = params.get("method");
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            http.sendError(exchange, 400, "dataFlow requires parameters 'class' and 'method'");
            return;
        }

        String depthMode = params.getOrDefault("depth_mode", "shallow");

        // Find the class and method
        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler == null) {
            http.sendError(exchange, 503, "Decompiler not ready");
            return;
        }

        Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
        JavaClass cls = CodeUtil.findClassDeeply(cache, className, decompiler);
        if (cls == null) {
            http.sendError(exchange, 404, "Class not found: " + className);
            return;
        }

        JavaMethod method = CodeUtil.findMethod(cls, methodName);
        if (method == null) {
            http.sendError(exchange, 404, "Method not found: " + methodName);
            return;
        }

        SSATaintAnalyzer taintAnalyzer = codeIndex.getSSATaintAnalyzer();
        SSATaintAnalyzer.TaintResult result = taintAnalyzer.analyzeMethod(cls, method, depthMode);

        http.sendResponse(exchange, 200, http.toJson(result.toMap()));
    }

    private void handleEntryPoints(HttpExchange exchange, Map<String, String> params) throws IOException {
        String filter = params.getOrDefault("filter", "all");

        List<Map<String, Object>> rawEntries = manifestAnalyzer.getEntryPoints(filter);

        // Enrich entries to match spec format
        List<Map<String, Object>> entries = new ArrayList<>();
        int unprotected = 0;
        int withDeeplinks = 0;
        String highestRisk = "low";

        var secAnnotator = layers.isReady(2) ? codeIndex.getSecurityAnnotator() : null;

        for (Map<String, Object> raw : rawEntries) {
            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("class_name", raw.get("name"));
            ep.put("name", raw.get("name")); // Go extractor compat
            ep.put("component_type", raw.get("type"));
            ep.put("type", raw.get("type")); // Go extractor compat
            ep.put("exported", raw.get("exported"));
            ep.put("has_permission", Boolean.TRUE.equals(raw.get("protected")));
            ep.put("has_intent_filter", raw.containsKey("intent_filters"));

            if (raw.containsKey("deep_links")) {
                ep.put("deep_links", raw.get("deep_links"));
                withDeeplinks++;
            }

            // Compute risk_level (enhanced with sink info)
            String risk = (String) raw.getOrDefault("risk", "low");

            // Enrich with security data from Layer 2
            if (secAnnotator != null) {
                String className = (String) raw.get("name");
                var classSummary = secAnnotator.getClassSummary(className);
                if (classSummary != null) {
                    ep.put("contains_sinks", new ArrayList<>(classSummary.sinkCategories));
                    ep.put("source_categories", new ArrayList<>(classSummary.sourceCategories));

                    // Upgrade risk level based on security data
                    if (classSummary.sinkCategories.contains("exec") || classSummary.sinkCategories.contains("sql")) {
                        risk = "critical";
                    } else if (classSummary.sinkCategories.contains("webview") &&
                            classSummary.sourceCategories.contains("deeplink")) {
                        risk = "critical";
                    } else if (!Boolean.TRUE.equals(raw.get("protected")) && classSummary.totalSinks > 0) {
                        if ("low".equals(risk) || "medium".equals(risk)) risk = "high";
                    }
                } else {
                    ep.put("contains_sinks", Collections.emptyList());
                    ep.put("source_categories", Collections.emptyList());
                }
            }

            ep.put("risk_level", risk);

            if (!Boolean.TRUE.equals(raw.get("protected"))) unprotected++;

            // Track highest risk
            if ("critical".equals(risk)) highestRisk = "critical";
            else if ("high".equals(risk) && !"critical".equals(highestRisk)) highestRisk = "high";
            else if ("medium".equals(risk) && "low".equals(highestRisk)) highestRisk = "medium";

            entries.add(ep);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "entry-points");
        result.put("filter", filter);
        result.put("entries", entries);
        result.put("summary", Map.of(
                "total_entries", entries.size(),
                "unprotected", unprotected,
                "with_deeplinks", withDeeplinks,
                "highest_risk", highestRisk
        ));
        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleAttackSurface(HttpExchange exchange, Map<String, String> params) throws IOException {
        var secAnnotator = codeIndex.getSecurityAnnotator();
        var apiIndex = codeIndex.getApiEndpointIndex();
        var archDetector = codeIndex.getArchitectureDetector();
        var callGraph = codeIndex.getCallGraph();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "attack-surface");

        // 1. Entry points section (per spec)
        List<Map<String, Object>> rawEntryPoints = manifestAnalyzer.getEntryPoints("all");
        Map<String, Object> entryPointsSection = new LinkedHashMap<>();
        entryPointsSection.put("total", rawEntryPoints.size());
        int unprotectedCount = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        List<String> allDeepLinks = new ArrayList<>();
        List<Map<String, Object>> topRisk = new ArrayList<>();

        for (Map<String, Object> ep : rawEntryPoints) {
            String type = (String) ep.get("type");
            byType.merge(type, 1, Integer::sum);

            if (!Boolean.TRUE.equals(ep.get("protected"))) unprotectedCount++;

            // Collect deep links
            @SuppressWarnings("unchecked")
            List<Map<String, String>> epDeepLinks = (List<Map<String, String>>) ep.get("deep_links");
            if (epDeepLinks != null) {
                for (Map<String, String> link : epDeepLinks) {
                    String scheme = link.getOrDefault("scheme", "");
                    String host = link.getOrDefault("host", "");
                    allDeepLinks.add(scheme + "://" + host);
                }
            }

            // Build top_risk entries for unprotected high-value components
            if (!Boolean.TRUE.equals(ep.get("protected"))) {
                String className = (String) ep.get("name");
                String reason = buildRiskReason(className, ep, secAnnotator);
                if (reason != null) {
                    String simpleName = className.contains(".") ?
                            className.substring(className.lastIndexOf('.') + 1) : className;
                    String risk = determineRisk(className, ep, secAnnotator);
                    if ("critical".equals(risk) || "high".equals(risk)) {
                        Map<String, Object> riskEntry = new LinkedHashMap<>();
                        riskEntry.put("name", simpleName);
                        riskEntry.put("risk", risk);
                        riskEntry.put("reason", reason);
                        topRisk.add(riskEntry);
                    }
                }
            }
        }

        entryPointsSection.put("unprotected", unprotectedCount);
        entryPointsSection.put("by_type", byType);
        entryPointsSection.put("deep_links", allDeepLinks);
        // Sort topRisk: critical first, then high
        topRisk.sort((a, b) -> {
            boolean aCrit = "critical".equals(a.get("risk"));
            boolean bCrit = "critical".equals(b.get("risk"));
            return Boolean.compare(bCrit, aCrit);
        });
        entryPointsSection.put("top_risk", topRisk.size() > 5 ? topRisk.subList(0, 5) : topRisk);
        result.put("entry_points", entryPointsSection);

        // 2. API endpoints (enhanced per spec)
        result.put("api_endpoints", apiIndex.getEnhancedSummary());

        // 3. Auth mechanisms (interceptor chain extraction per spec section 7.1.6)
        result.put("auth_mechanisms", apiIndex.getAuthMechanisms());

        // 4. Sink distribution (per-category with details)
        String[] categories = {"crypto", "webview", "exec", "sql", "file", "intent", "network", "dynamic_code", "log"};
        Map<String, Object> sinkDist = new LinkedHashMap<>();
        var ruleEngineForSinks = layers.isReady(3) ? codeIndex.getRuleEngine() : null;
        for (String cat : categories) {
            List<Map<String, Object>> sinks = secAnnotator.findSinks(cat);
            if (sinks.isEmpty()) {
                sinkDist.put(cat, Map.of("count", 0));
            } else {
                Map<String, Object> catInfo = new LinkedHashMap<>();
                catInfo.put("count", sinks.size());
                // Count unique classes
                Set<String> uniqueClasses = new HashSet<>();
                for (Map<String, Object> s : sinks) {
                    uniqueClasses.add((String) s.get("class_name"));
                }
                catInfo.put("classes", uniqueClasses.size());
                // Determine highest_severity from rule findings for this category
                if (ruleEngineForSinks != null) {
                    String highest = "low";
                    for (var finding : ruleEngineForSinks.getFindings(cat, "info")) {
                        if ("critical".equals(finding.severity)) { highest = "critical"; break; }
                        if ("high".equals(finding.severity) && !"critical".equals(highest)) highest = "high";
                        if ("medium".equals(finding.severity) && "low".equals(highest)) highest = "medium";
                    }
                    catInfo.put("highest_severity", highest);
                }
                sinkDist.put(cat, catInfo);
            }
        }
        // Wrap sinkDist under "categories" for Go extractor, and also keep flat keys
        Map<String, Object> sinkDistWrapper = new LinkedHashMap<>();
        sinkDistWrapper.put("categories", sinkDist);
        sinkDistWrapper.putAll(sinkDist); // also expose flat keys for LLM readability
        result.put("sink_distribution", sinkDistWrapper);

        // 5. App architecture
        result.put("app_architecture", archDetector.getResultAsMap());

        // 6. Rule scan summary
        if (layers.isReady(3)) {
            var ruleEngine = codeIndex.getRuleEngine();
            Map<String, Object> scanSummary = new LinkedHashMap<>(ruleEngine.getSummary());

            // Add top findings
            var topFindings = ruleEngine.getFindingsAsMap(null, "high", 5);
            if (!topFindings.isEmpty()) {
                scanSummary.put("top_findings", topFindings);
            }
            result.put("rule_scan_summary", scanSummary);
        }

        // 7. Suggested analysis priorities (intelligent generation)
        List<String> priorities = generatePriorities(topRisk, sinkDist, secAnnotator);
        result.put("suggested_analysis_priorities", priorities);

        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private String buildRiskReason(String className, Map<String, Object> ep,
                                    SecurityAnnotator secAnnotator) {
        var classSummary = secAnnotator.getClassSummary(className);
        if (classSummary == null) return null;

        if (classSummary.sourceCategories.contains("deeplink") &&
                classSummary.sinkCategories.contains("webview")) {
            return "deep link → webview, no filter";
        }
        if ("provider".equals(ep.get("type"))) {
            return "exported provider, no permission";
        }
        if (classSummary.sinkCategories.contains("exec")) {
            return "contains command execution sinks";
        }
        if (classSummary.sinkCategories.contains("sql")) {
            return "contains SQL sinks, potential injection";
        }
        if (classSummary.totalSinks > 0) {
            return "unprotected with " + classSummary.totalSinks + " sink calls";
        }
        return "exported without permission";
    }

    private String determineRisk(String className, Map<String, Object> ep,
                                  SecurityAnnotator secAnnotator) {
        var classSummary = secAnnotator.getClassSummary(className);
        if (classSummary != null) {
            if (classSummary.sinkCategories.contains("exec") || classSummary.sinkCategories.contains("sql")) {
                return "critical";
            }
            if (classSummary.sourceCategories.contains("deeplink") &&
                    classSummary.sinkCategories.contains("webview")) {
                return "critical";
            }
        }
        if ("provider".equals(ep.get("type"))) return "critical";
        return "high";
    }

    @SuppressWarnings("unchecked")
    private List<String> generatePriorities(List<Map<String, Object>> topRisk,
                                             Map<String, Object> sinkDist,
                                             SecurityAnnotator secAnnotator) {
        List<String> priorities = new ArrayList<>();
        int idx = 1;

        // Add top risk components
        for (Map<String, Object> risk : topRisk) {
            if (idx > 5) break;
            priorities.add(idx + ". " + risk.get("name") + ": " + risk.get("reason"));
            idx++;
        }

        // Add sink-based priorities if room
        Map<String, Object> execInfo = (Map<String, Object>) sinkDist.get("exec");
        if (idx <= 5 && execInfo != null && (int) execInfo.getOrDefault("count", 0) > 0) {
            priorities.add(idx + ". Command injection analysis (exec sinks found)");
            idx++;
        }
        Map<String, Object> sqlInfo = (Map<String, Object>) sinkDist.get("sql");
        if (idx <= 5 && sqlInfo != null && (int) sqlInfo.getOrDefault("count", 0) > 0) {
            priorities.add(idx + ". SQL injection analysis (sql sinks found)");
            idx++;
        }

        return priorities;
    }

    private void handleResolveDI(HttpExchange exchange, Map<String, String> params) throws IOException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            http.sendError(exchange, 400, "resolveDI requires parameter 'name' (interface name)");
            return;
        }

        var diResolver = codeIndex.getDIBindingResolver();
        Set<String> implementations = diResolver.resolveInterface(name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interface", name);
        result.put("implementations", new ArrayList<>(implementations));
        result.put("implementation_count", implementations.size());

        // Get constructor dependencies for each implementation
        List<Map<String, Object>> details = new ArrayList<>();
        for (String impl : implementations) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("class", impl);
            List<String> deps = diResolver.getConstructorDependencies(impl);
            if (!deps.isEmpty()) {
                detail.put("constructor_dependencies", deps);
            }
            details.add(detail);
        }
        result.put("binding_details", details);

        // List all modules that define bindings
        result.put("modules", new ArrayList<>(diResolver.getModuleClasses()));

        http.sendResponse(exchange, 200, http.toJson(result));
    }
}
