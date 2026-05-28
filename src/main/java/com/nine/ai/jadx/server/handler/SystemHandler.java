package com.nine.ai.jadx.server.handler;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.core.ManifestAnalyzer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * /system 路由处理器
 * Actions: status, overview, rename, clearCache, reloadRules
 */
public class SystemHandler extends BaseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SystemHandler.class);

    private final CodeIndexManager codeIndex;
    private final MainWindow mainWindow;
    private final ManifestAnalyzer manifestAnalyzer;
    private final long serverStartTime;

    // Cached overview
    private volatile Map<String, Object> cachedOverview = null;

    public SystemHandler(AnalysisLayers layers, CodeIndexManager codeIndex, MainWindow mainWindow, long startTime, ManifestAnalyzer manifestAnalyzer) {
        super(layers);
        this.codeIndex = codeIndex;
        this.mainWindow = mainWindow;
        this.manifestAnalyzer = manifestAnalyzer;
        this.serverStartTime = startTime;
    }

    @Override
    protected int requiredLayer(String action) {
        // status and clearCache/reloadRules have no layer dependency
        switch (action) {
            case "status":
            case "clearCache":
            case "reloadRules":
                return -1;
            case "overview":
                return 0;
            case "rename":
                return 0;
            default:
                return -1;
        }
    }

    @Override
    protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
        switch (action) {
            case "status":
                handleStatus(exchange);
                break;
            case "overview":
                handleOverview(exchange);
                break;
            case "rename":
                handleRename(exchange, params);
                break;
            case "clearCache":
                handleClearCache(exchange);
                break;
            case "reloadRules":
                handleReloadRules(exchange, params);
                break;
            default:
                http.sendError(exchange, 400,
                        "Unknown action for /system: '" + action + "'. Valid: status, overview, rename, clearCache, reloadRules");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health", "UP");

        JadxDecompiler decompiler = JadxUtil.getDecompiler(false);
        result.put("decompiler_ready", decompiler != null);

        // Memory
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        result.put("memory", Map.of(
                "used_mb", usedMb,
                "max_mb", maxMb,
                "usage_percent", (int) ((usedMb * 100) / maxMb)
        ));

        // Layer status (detailed per spec)
        result.put("layers", layers.getStatusReport());

        // Architecture detection (if available from Layer 2)
        if (layers.isReady(2)) {
            result.put("app_architecture", codeIndex.getArchitectureDetector().getResultAsMap());
        }

        // Libraries detected (per spec)
        if (layers.isReady(1)) {
            result.put("libraries_detected", codeIndex.getDetectedLibraries());
        }

        // Library stats
        result.put("third_party_classes", layers.getThirdPartyClasses());
        result.put("app_classes", layers.getAppClasses());

        // Uptime
        long uptimeSec = (System.currentTimeMillis() - serverStartTime) / 1000;
        result.put("uptime_seconds", uptimeSec);

        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleOverview(HttpExchange exchange) throws IOException {
        if (cachedOverview != null) {
            http.sendResponse(exchange, 200, http.toJson(cachedOverview));
            return;
        }

        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler == null) {
            http.sendError(exchange, 503, "Decompiler not ready");
            return;
        }

        Map<String, Object> overview = new LinkedHashMap<>();

        // Manifest data from ManifestAnalyzer (Layer 0)
        if (manifestAnalyzer.isParsed()) {
            Map<String, Object> summary = manifestAnalyzer.getCompactSummary();
            overview.putAll(summary);
        }

        // Code statistics
        var classes = new java.util.ArrayList<>(decompiler.getClassesWithInners());
        overview.put("total_classes", classes.size());
        overview.put("total_methods", classes.stream().mapToInt(c -> {
            try { return c.getClassNode().getMethods().size(); }
            catch (Exception e) { return 0; }
        }).sum());

        // Package summary (top-level packages)
        Map<String, Integer> packageCounts = new TreeMap<>();
        for (var cls : classes) {
            String pkg = cls.getFullName();
            int dot = pkg.indexOf('.');
            if (dot > 0) {
                int dot2 = pkg.indexOf('.', dot + 1);
                String topPkg = dot2 > 0 ? pkg.substring(0, dot2) : pkg.substring(0, dot);
                packageCounts.merge(topPkg, 1, Integer::sum);
            }
        }
        overview.put("packages", packageCounts);

        // Libraries detected (Layer 1)
        if (layers.isReady(1)) {
            overview.put("libraries_detected", codeIndex.getDetectedLibraries());
        }

        cachedOverview = overview;
        http.sendResponse(exchange, 200, http.toJson(overview));
    }

    private void handleRename(HttpExchange exchange, Map<String, String> params) throws IOException {
        String type = params.get("type");
        if (type == null || type.isBlank()) {
            http.sendError(exchange, 400, "rename requires parameter 'type' (class/method/field/variable/export)");
            return;
        }

        if ("export".equals(type)) {
            // Export all rename mappings
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "rename_export");
            result.put("mappings", CodeUtil.getRenameMapping());
            result.put("total", CodeUtil.getRenameMapping().size());
            http.sendResponse(exchange, 200, http.toJson(result));
            return;
        }

        String target = params.get("target");
        String newName = params.get("new_name");
        if (target == null || target.isBlank() || newName == null || newName.isBlank()) {
            http.sendError(exchange, 400, "rename requires parameters 'target' and 'new_name'");
            return;
        }

        // Perform rename via JADX GUI thread
        try {
            boolean success = performRename(type, target, newName, params);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("type", type);
            result.put("target", target);
            result.put("new_name", newName);
            if (success) {
                CodeUtil.recordRename(newName, target);
            }
            http.sendResponse(exchange, 200, http.toJson(result));
        } catch (Exception e) {
            http.sendError(exchange, 500, "Rename failed: " + e.getMessage());
        }
    }

    private void handleClearCache(HttpExchange exchange) throws IOException {
        JadxUtil.clearCaches();
        codeIndex.invalidate();
        cachedOverview = null;
        manifestAnalyzer.clearCache();
        layers.reset();

        Map<String, Object> result = Map.of(
                "success", true,
                "message", "All caches cleared. Layers will rebuild on next request."
        );
        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleReloadRules(HttpExchange exchange, Map<String, String> params) throws IOException {
        String path = params.get("path");
        var ruleEngine = codeIndex.getRuleEngine();
        int loaded;
        if (path != null && !path.isBlank()) {
            loaded = ruleEngine.reloadRules(java.nio.file.Path.of(path));
        } else {
            loaded = ruleEngine.reloadRules(null);
        }

        // Re-scan if code index is available
        Map<String, String> index = codeIndex.getCodeIndex();
        int findings = 0;
        if (index != null && !index.isEmpty()) {
            var scanResult = ruleEngine.scanAll(index, codeIndex.getCallGraph(), codeIndex.getSecurityAnnotator());
            findings = scanResult.totalFindings;
            layers.setRuleEngineStats(loaded, findings);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rules_loaded", loaded);
        result.put("findings_after_rescan", findings);
        http.sendResponse(exchange, 200, http.toJson(result));
    }

    // ==================== Rename helpers ====================

    private boolean performRename(String type, String target, String newName, Map<String, String> params) {
        if (mainWindow == null) return false;

        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler == null) return false;

        Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);

        switch (type) {
            case "class": {
                JavaClass cls = CodeUtil.findClassDeeply(cache, target, decompiler);
                if (cls == null) return false;
                cls.getClassNode().rename(newName);
                return true;
            }
            case "method": {
                String[] parts = target.split("#");
                if (parts.length < 2) return false;
                JavaClass cls = CodeUtil.findClassDeeply(cache, parts[0], decompiler);
                if (cls == null) return false;
                JavaMethod method = CodeUtil.findMethod(cls, parts[1]);
                if (method == null) return false;
                method.getMethodNode().rename(newName);
                return true;
            }
            case "field": {
                String[] parts = target.split("#");
                if (parts.length < 2) return false;
                JavaClass cls = CodeUtil.findClassDeeply(cache, parts[0], decompiler);
                if (cls == null) return false;
                JavaField field = CodeUtil.findField(cls, parts[1]);
                if (field == null) return false;
                field.getFieldNode().rename(newName);
                return true;
            }
            case "variable": {
                // target format: com.app.Class#method#oldVarName
                String[] parts = target.split("#");
                if (parts.length < 3) return false;
                JavaClass cls = CodeUtil.findClassDeeply(cache, parts[0], decompiler);
                if (cls == null) return false;
                JavaMethod method = CodeUtil.findMethod(cls, parts[1]);
                if (method == null) return false;

                MethodNode mth = method.getMethodNode();
                List<SSAVar> ssaVars = mth.getSVars();
                if (ssaVars == null || ssaVars.isEmpty()) return false;

                String oldVarName = parts[2];
                String regParam = params.get("reg");
                String ssaParam = params.get("ssa");

                // Find matching SSAVar by name, reg, or ssa version
                SSAVar targetVar = null;
                for (SSAVar var : ssaVars) {
                    // Match by reg + ssa if both specified (most precise)
                    if (regParam != null && ssaParam != null) {
                        try {
                            int reg = Integer.parseInt(regParam);
                            int ssa = Integer.parseInt(ssaParam);
                            if (var.getRegNum() == reg && var.getVersion() == ssa) {
                                targetVar = var;
                                break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    // Match by reg only
                    else if (regParam != null) {
                        try {
                            int reg = Integer.parseInt(regParam);
                            if (var.getRegNum() == reg) {
                                targetVar = var;
                                break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    // Match by variable name
                    else {
                        String varName = var.getName();
                        if (oldVarName.equals(varName)) {
                            targetVar = var;
                            break;
                        }
                    }
                }

                if (targetVar == null) return false;
                targetVar.setName(newName);
                return true;
            }
            default:
                return false;
        }
    }

    public void clearOverviewCache() {
        cachedOverview = null;
    }
}
