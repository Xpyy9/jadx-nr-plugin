package com.nine.ai.jadx.server.handler;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.util.CodeUtil;
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
 * /code 路由处理器
 * Actions: getClass, getMethod, batchGetClass
 */
public class CodeHandler extends BaseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CodeHandler.class);

    private final CodeIndexManager codeIndex;

    public CodeHandler(AnalysisLayers layers, CodeIndexManager codeIndex) {
        super(layers);
        this.codeIndex = codeIndex;
    }

    @Override
    protected int requiredLayer(String action) {
        // All code actions need at least Layer 0 (manifest).
        // Layer 2 provides security tags — return enriched data if ready, basic otherwise.
        return 0;
    }

    @Override
    protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
        switch (action) {
            case "getClass":
                handleGetClass(exchange, params);
                break;
            case "getMethod":
                handleGetMethod(exchange, params);
                break;
            case "batchGetClass":
                handleBatchGetClass(exchange, params);
                break;
            default:
                http.sendError(exchange, 400, "Unknown action for /code: '" + action + "'. Valid: getClass, getMethod, batchGetClass");
        }
    }

    private void handleGetClass(HttpExchange exchange, Map<String, String> params) throws IOException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            http.sendError(exchange, 400, "getClass requires parameter 'name'");
            return;
        }

        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler == null) {
            http.sendError(exchange, 503, "Decompiler not ready");
            return;
        }

        Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
        JavaClass cls = CodeUtil.findClassDeeply(cache, name, decompiler);
        if (cls == null) {
            http.sendError(exchange, 404, "Class not found: " + name);
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class_name", cls.getFullName());

        // Super class
        try {
            var superType = cls.getClassNode().getSuperClass();
            result.put("super_class", superType != null ? superType.getObject().replace('/', '.') : "java.lang.Object");
        } catch (Exception e) {
            result.put("super_class", "java.lang.Object");
        }

        // Interfaces
        try {
            List<String> interfaces = new ArrayList<>();
            for (var iface : cls.getClassNode().getInterfaces()) {
                interfaces.add(iface.getObject().replace('/', '.'));
            }
            result.put("implements", interfaces);
        } catch (Exception e) {
            result.put("implements", Collections.emptyList());
        }

        // Structure object: methods + fields (per spec 2.2)
        Map<String, Object> structure = new LinkedHashMap<>();

        // Fields
        List<Map<String, Object>> fields = new ArrayList<>();
        for (var f : cls.getFields()) {
            Map<String, Object> fInfo = new LinkedHashMap<>();
            fInfo.put("name", f.getName());
            fInfo.put("type", f.getType());
            fields.add(fInfo);
        }
        structure.put("fields", fields);

        // Methods with security tags
        List<Map<String, Object>> methods = new ArrayList<>();
        for (JavaMethod m : cls.getMethods()) {
            Map<String, Object> mInfo = new LinkedHashMap<>();
            mInfo.put("name", m.getName());
            try {
                mInfo.put("signature", m.getMethodNode().getMethodInfo().getShortId());
            } catch (Exception e) {
                mInfo.put("signature", m.getName());
            }
            mInfo.put("access", getAccessString(m));

            // Security tags from Layer 2 (if ready)
            if (layers.isReady(2)) {
                Map<String, Object> tags = getSecurityTags(cls.getFullName(), m.getName());
                if (tags != null && !tags.isEmpty()) {
                    mInfo.put("security_tags", tags);
                }
            }

            methods.add(mInfo);
        }
        structure.put("methods", methods);

        result.put("structure", structure);

        // Also expose methods/fields at top level for Go extractor compatibility
        result.put("methods", methods);
        result.put("fields", fields);

        // Code
        String code = cls.getCode();
        result.put("code", code != null ? code : "// Code not available");

        // Class-level security summary (if Layer 2 ready)
        if (layers.isReady(2)) {
            Map<String, Object> summary = getClassSecuritySummary(cls.getFullName());
            if (summary != null) {
                result.put("class_security_summary", summary);
            }
        }

        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleGetMethod(HttpExchange exchange, Map<String, String> params) throws IOException {
        String className = params.get("class");
        String methodName = params.get("method");
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            http.sendError(exchange, 400, "getMethod requires parameters 'class' and 'method'");
            return;
        }

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
            http.sendError(exchange, 404, "Method not found: " + methodName + " in " + className);
            return;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class_name", cls.getFullName());
        result.put("method_name", method.getName());
        try {
            String sig = method.getMethodNode().getMethodInfo().getShortId();
            result.put("method_signature", sig);
            result.put("signature", sig); // Go extractor compat
        } catch (Exception e) {
            result.put("method_signature", method.getName());
            result.put("signature", method.getName());
        }

        // Method code
        try {
            String methodCode = method.getCodeStr();
            result.put("code", methodCode != null ? methodCode : "// Code not available");
        } catch (Exception e) {
            result.put("code", "// Failed to decompile method: " + e.getMessage());
        }

        // Security tags (Layer 2) + taint info (Layer 4 on-demand)
        if (layers.isReady(2)) {
            Map<String, Object> tags = getSecurityTags(cls.getFullName(), method.getName());
            if (tags == null) tags = new LinkedHashMap<>();

            // Add taint info from SSATaintAnalyzer (on-demand, per spec getMethod response)
            try {
                var taintAnalyzer = codeIndex.getSSATaintAnalyzer();
                var taintResult = taintAnalyzer.analyzeMethod(cls, method, "shallow");
                tags.put("has_tainted_params", !taintResult.taintedParams.isEmpty());
                if (!taintResult.taintedParams.isEmpty() || !taintResult.flows.isEmpty()) {
                    StringBuilder summary = new StringBuilder();
                    if (!taintResult.taintedParams.isEmpty()) {
                        summary.append("参数 ").append(taintResult.taintedParams).append(" 可能来自外部输入");
                    }
                    if (!taintResult.flows.isEmpty()) {
                        if (summary.length() > 0) summary.append("; ");
                        summary.append(taintResult.flows.size()).append(" 条污点路径");
                    }
                    tags.put("taint_summary", summary.toString());
                }
            } catch (Exception e) {
                // Taint analysis failure is non-critical
            }

            if (!tags.isEmpty()) {
                result.put("security_tags", tags);
            }
        }

        // Callers (Layer 2 - call graph reverse lookup)
        if (layers.isReady(2)) {
            List<Map<String, String>> callers = getMethodCallers(cls.getFullName(), method.getName());
            result.put("callers", callers);
            result.put("caller_count", callers.size());
        }

        http.sendResponse(exchange, 200, http.toJson(result));
    }

    private void handleBatchGetClass(HttpExchange exchange, Map<String, String> params) throws IOException {
        String names = params.get("names");
        if (names == null || names.isBlank()) {
            http.sendError(exchange, 400, "batchGetClass requires parameter 'names' (comma-separated, max 5)");
            return;
        }

        String[] nameList = names.split(",");
        if (nameList.length > 5) {
            http.sendError(exchange, 400, "batchGetClass supports max 5 classes, got " + nameList.length);
            return;
        }

        JadxDecompiler decompiler = JadxUtil.getDecompiler();
        if (decompiler == null) {
            http.sendError(exchange, 503, "Decompiler not ready");
            return;
        }

        Map<String, JavaClass> cache = CodeUtil.initClassCache(decompiler);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String n : nameList) {
            String trimmed = n.trim();
            if (trimmed.isEmpty()) continue;

            JavaClass cls = CodeUtil.findClassDeeply(cache, trimmed, decompiler);
            if (cls == null) {
                Map<String, Object> errEntry = new LinkedHashMap<>();
                errEntry.put("class_name", trimmed);
                errEntry.put("error", "not found");
                results.add(errEntry);
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class_name", cls.getFullName());

            // Structure only (no code body - prevent token explosion)
            List<Map<String, Object>> methods = new ArrayList<>();
            for (JavaMethod m : cls.getMethods()) {
                Map<String, Object> mInfo = new LinkedHashMap<>();
                mInfo.put("name", m.getName());
                mInfo.put("access", getAccessString(m));
                if (layers.isReady(2)) {
                    Map<String, Object> tags = getSecurityTags(cls.getFullName(), m.getName());
                    if (tags != null && !tags.isEmpty()) {
                        mInfo.put("security_tags", tags);
                    }
                }
                methods.add(mInfo);
            }
            entry.put("methods", methods);
            entry.put("method_count", cls.getMethods().size());
            entry.put("field_count", cls.getFields().size());

            if (layers.isReady(2)) {
                Map<String, Object> summary = getClassSecuritySummary(cls.getFullName());
                if (summary != null) {
                    entry.put("class_security_summary", summary);
                }
            }

            results.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "batch-class-structure");
        response.put("results", results);
        response.put("count", results.size());

        http.sendResponse(exchange, 200, http.toJson(response));
    }

    // ==================== Helper methods ====================

    private String getAccessString(JavaMethod m) {
        try {
            var flags = m.getAccessFlags();
            if (flags.isPublic()) return "public";
            if (flags.isProtected()) return "protected";
            if (flags.isPrivate()) return "private";
        } catch (Exception ignored) {}
        return "package";
    }

    /**
     * Get security tags for a method from SecurityAnnotator (Layer 2) + RuleEngine (Layer 3).
     */
    private Map<String, Object> getSecurityTags(String className, String methodName) {
        String methodKey = className + "#" + methodName;
        var ruleEngine = layers.isReady(3) ? codeIndex.getRuleEngine() : null;
        return codeIndex.getSecurityAnnotator().getTagAsMap(methodKey, ruleEngine);
    }

    /**
     * Get class-level security summary from SecurityAnnotator (Layer 2) + RuleEngine (Layer 3).
     */
    private Map<String, Object> getClassSecuritySummary(String className) {
        var ruleEngine = layers.isReady(3) ? codeIndex.getRuleEngine() : null;
        return codeIndex.getSecurityAnnotator().getClassSummaryAsMap(className, ruleEngine);
    }

    /**
     * Get callers of a method from CallGraph (Layer 2).
     */
    private List<Map<String, String>> getMethodCallers(String className, String methodName) {
        String methodKey = className + "#" + methodName;
        Set<String> callers = codeIndex.getCallGraph().getCallers(methodKey);
        List<Map<String, String>> result = new ArrayList<>();
        for (String caller : callers) {
            Map<String, String> entry = new LinkedHashMap<>();
            int hash = caller.indexOf('#');
            if (hash > 0) {
                entry.put("class_name", caller.substring(0, hash));
                entry.put("method_name", caller.substring(hash + 1));
                entry.put("method_signature", caller.substring(hash + 1));
            } else {
                entry.put("class_name", caller);
                entry.put("method_name", "");
                entry.put("method_signature", "");
            }
            result.add(entry);
        }
        return result;
    }
}
