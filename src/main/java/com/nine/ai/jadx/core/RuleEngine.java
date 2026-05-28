package com.nine.ai.jadx.core;

import com.nine.ai.jadx.core.RuleParser.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Layer 3: 安全规则引擎。
 * 加载 YAML 规则文件，对 CodeIndex 执行全量扫描，缓存结果。
 *
 * 支持:
 * - loadRules(dir): 加载规则目录或classpath bundled rules
 * - scanAll(codeIndex, callGraph, secAnnotator): 全量扫描
 * - scanClass(className, code, invokedMethods): 单类扫描
 * - getFindings(category, severity): 按条件过滤
 * - reloadRules(): 热加载
 */
public class RuleEngine {
    private static final Logger LOG = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleParser parser = new RuleParser();
    private volatile List<SecurityRule> rules = new ArrayList<>();

    // className → List<RuleMatch>
    private final ConcurrentHashMap<String, List<RuleMatch>> classCache = new ConcurrentHashMap<>();

    // All findings (flattened)
    private final List<RuleMatch> allFindings = Collections.synchronizedList(new ArrayList<>());

    // Severity counters
    private volatile int criticalCount = 0;
    private volatile int highCount = 0;
    private volatile int mediumCount = 0;
    private volatile int infoCount = 0;

    // Bundled rule resources
    private static final String[] BUNDLED_RULES = {
            "rules/crypto.yaml",
            "rules/ssl_tls.yaml",
            "rules/webview.yaml",
            "rules/ipc_security.yaml",
            "rules/dynamic_code.yaml",
            "rules/data_storage.yaml",
            "rules/data_leak.yaml",
            "rules/logging.yaml",
            "rules/network.yaml",
            "rules/hardcoded_secrets.yaml",
            "rules/root_detection.yaml"
    };

    // ==================== Load API ====================

    /**
     * Load rules from bundled resources (classpath).
     */
    public int loadBundledRules() {
        List<SecurityRule> loaded = new ArrayList<>();
        for (String res : BUNDLED_RULES) {
            try {
                List<SecurityRule> parsed = parser.parseResource(res);
                loaded.addAll(parsed);
            } catch (Exception e) {
                LOG.warn("Skipping bundled rule: {}", res);
            }
        }
        this.rules = loaded;
        LOG.info("Loaded {} bundled rules", loaded.size());
        return loaded.size();
    }

    /**
     * Load rules from external directory (overrides bundled rules).
     */
    public int loadRules(Path rulesDir) {
        List<SecurityRule> loaded = parser.parseDirectory(rulesDir);
        if (loaded.isEmpty()) {
            LOG.info("No external rules found in {}, using bundled rules", rulesDir);
            return loadBundledRules();
        }
        this.rules = loaded;
        LOG.info("Loaded {} rules from {}", loaded.size(), rulesDir);
        return loaded.size();
    }

    /**
     * Hot-reload rules (clears cache, re-scans not triggered automatically).
     */
    public int reloadRules(Path rulesDir) {
        clearFindings();
        if (rulesDir != null) {
            return loadRules(rulesDir);
        }
        return loadBundledRules();
    }

    // ==================== Scan API ====================

    /**
     * Full scan: iterate all classes in the code index and evaluate all rules.
     */
    public ScanResult scanAll(Map<String, String> codeIndex, CallGraph callGraph,
                               SecurityAnnotator secAnnotator) {
        clearFindings();
        long start = System.currentTimeMillis();

        codeIndex.entrySet().parallelStream().forEach(entry -> {
            String className = entry.getKey();
            String code = entry.getValue();

            // Build context for this class
            Set<String> invokedMethods = new HashSet<>();
            Set<String> interfaces = new HashSet<>();
            String superClass = null;

            // Get invoked methods from SecurityAnnotator tags
            SecurityAnnotator.SecurityTag tag = secAnnotator.getTag(className + "#*");
            // Build from callGraph forward edges
            Set<String> callees = callGraph.getCallees(className + "#*");
            if (callees != null) invokedMethods.addAll(callees);

            // Also try to extract method-level info
            // For class-level scan, we aggregate all method invocations
            for (String methodKey : codeIndex.keySet()) {
                if (!methodKey.startsWith(className + "#")) continue;
                Set<String> mCallees = callGraph.getCallees(methodKey);
                if (mCallees != null) invokedMethods.addAll(mCallees);
            }

            // Extract interfaces and superclass from code (heuristic)
            extractClassInfo(code, interfaces);

            MatchContext ctx = new MatchContext(
                    className, "*", code,
                    invokedMethods, interfaces, Collections.emptySet(), superClass
            );

            List<RuleMatch> matches = evaluateRules(ctx);
            if (!matches.isEmpty()) {
                classCache.put(className, matches);
                allFindings.addAll(matches);
            }
        });

        // Count severities
        updateSeverityCounts();

        long elapsed = System.currentTimeMillis() - start;
        ScanResult result = new ScanResult();
        result.totalFindings = allFindings.size();
        result.criticalCount = criticalCount;
        result.highCount = highCount;
        result.mediumCount = mediumCount;
        result.infoCount = infoCount;
        result.rulesEvaluated = rules.size();
        result.classesScanned = codeIndex.size();
        result.elapsedMs = elapsed;

        LOG.info("Rule scan complete: {} findings ({} critical, {} high) in {}ms",
                result.totalFindings, result.criticalCount, result.highCount, elapsed);

        return result;
    }

    /**
     * Scan a single class (used when returning getClass results).
     */
    public List<RuleMatch> scanClass(String className, String code,
                                      Set<String> invokedMethods, Set<String> interfaces) {
        // Check cache first
        List<RuleMatch> cached = classCache.get(className);
        if (cached != null) return cached;

        MatchContext ctx = new MatchContext(
                className, "*", code,
                invokedMethods, interfaces, Collections.emptySet(), null
        );

        List<RuleMatch> matches = evaluateRules(ctx);
        if (!matches.isEmpty()) {
            classCache.put(className, matches);
        }
        return matches;
    }

    /**
     * Scan manifest components against manifest_check rules.
     * Called after scanAll to evaluate rules that depend on manifest metadata.
     */
    public void scanManifest(ManifestAnalyzer manifest) {
        if (manifest == null || !manifest.isParsed()) return;

        List<Map<String, Object>> entryPoints = manifest.getEntryPoints("all");
        for (Map<String, Object> ep : entryPoints) {
            String className = (String) ep.get("name");
            String type = (String) ep.get("type");
            boolean exported = Boolean.TRUE.equals(ep.get("exported"));
            boolean hasPermission = Boolean.TRUE.equals(ep.get("protected"));

            MatchContext ctx = new MatchContext(
                    className, "*", null,
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), null,
                    type, exported, hasPermission
            );

            List<RuleMatch> matches = evaluateRules(ctx);
            if (!matches.isEmpty()) {
                classCache.merge(className, matches, (old, nw) -> {
                    List<RuleMatch> combined = new ArrayList<>(old);
                    combined.addAll(nw);
                    return combined;
                });
                allFindings.addAll(matches);
            }
        }

        // Re-compute severity counts after adding manifest findings
        updateSeverityCounts();
    }

    // ==================== Query API ====================

    /**
     * Get findings filtered by category and minimum severity.
     */
    public List<RuleMatch> getFindings(String category, String minSeverity) {
        int minLevel = severityLevel(minSeverity);

        return allFindings.stream()
                .filter(m -> category == null || "all".equals(category) || m.category.equals(category))
                .filter(m -> severityLevel(m.severity) >= minLevel)
                .collect(Collectors.toList());
    }

    /**
     * Get findings for a specific class.
     */
    public List<RuleMatch> getClassFindings(String className) {
        return classCache.getOrDefault(className, Collections.emptyList());
    }

    /**
     * Get summary counts.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_findings", allFindings.size());
        summary.put("critical", criticalCount);
        summary.put("high", highCount);
        summary.put("medium", mediumCount);
        summary.put("info", infoCount);
        summary.put("rules_loaded", rules.size());

        // Category breakdown
        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (RuleMatch m : allFindings) {
            byCat.merge(m.category, 1, Integer::sum);
        }
        summary.put("by_category", byCat);

        return summary;
    }

    /**
     * Get all findings as serializable list.
     */
    public List<Map<String, Object>> getFindingsAsMap(String category, String minSeverity, int limit) {
        List<RuleMatch> filtered = getFindings(category, minSeverity);
        List<Map<String, Object>> result = new ArrayList<>();

        int count = 0;
        for (RuleMatch match : filtered) {
            if (count >= limit) break;
            result.add(match.toMap());
            count++;
        }
        return result;
    }

    public int getRuleCount() {
        return rules.size();
    }

    public void clearFindings() {
        classCache.clear();
        allFindings.clear();
        criticalCount = highCount = mediumCount = infoCount = 0;
    }

    // ==================== Internal ====================

    private List<RuleMatch> evaluateRules(MatchContext ctx) {
        List<RuleMatch> matches = new ArrayList<>();
        for (SecurityRule rule : rules) {
            try {
                if (rule.matcher != null && rule.matcher.matches(ctx)) {
                    RuleMatch match = new RuleMatch();
                    match.ruleId = rule.id;
                    match.ruleName = rule.name;
                    match.severity = rule.severity;
                    match.category = rule.category;
                    match.description = rule.description;
                    match.remediation = rule.remediation;
                    match.tags = rule.tags;
                    match.className = ctx.className;
                    match.methodName = ctx.methodName;
                    match.matchType = rule.matcher.describe().contains(":") ?
                            rule.matcher.describe().substring(0, rule.matcher.describe().indexOf(':')) : "pattern";
                    match.confidence = "high";

                    // Extract context lines and line number around the match
                    extractContextInfo(ctx.sourceCode, rule, match);

                    matches.add(match);
                }
            } catch (Exception e) {
                // Single rule evaluation failure should not break scan
            }
        }
        return matches;
    }

    /**
     * Extract 3-line context array and line number for a rule match.
     * Format per spec: ["41:     line before", "42: >>> matched line", "43:     line after"]
     */
    private void extractContextInfo(String sourceCode, SecurityRule rule, RuleMatch match) {
        if (sourceCode == null || rule.matcher == null) return;

        String searchTerm = null;

        if (rule.matcher instanceof MethodInvokeMatcher) {
            String target = ((MethodInvokeMatcher) rule.matcher).describe();
            searchTerm = target.contains(":") ? target.split(":")[1].trim() : target;
            searchTerm = searchTerm.substring(searchTerm.lastIndexOf('.') + 1);
            // Remove " args:..." suffix if present
            if (searchTerm.contains(" ")) searchTerm = searchTerm.substring(0, searchTerm.indexOf(' '));
        } else if (rule.matcher instanceof StringContainsMatcher) {
            String desc = rule.matcher.describe();
            searchTerm = desc.contains(":") ? desc.substring(desc.indexOf(':') + 1) : desc;
        } else if (rule.matcher instanceof ClassInheritMatcher) {
            String desc = rule.matcher.describe();
            searchTerm = desc.contains(":") ? desc.substring(desc.indexOf(':') + 1) : desc;
            String simpleName = searchTerm.contains(".") ?
                    searchTerm.substring(searchTerm.lastIndexOf('.') + 1) : searchTerm;
            searchTerm = simpleName;
        }

        if (searchTerm == null || searchTerm.isEmpty()) return;

        int idx = sourceCode.indexOf(searchTerm);
        if (idx < 0) return;

        // Split source into lines and find which line contains the match
        String[] lines = sourceCode.split("\n");
        int charCount = 0;
        int matchLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            charCount += lines[i].length() + 1; // +1 for \n
            if (charCount > idx && matchLineIdx < 0) {
                matchLineIdx = i;
            }
        }
        if (matchLineIdx < 0) return;

        match.lineNumber = matchLineIdx + 1; // 1-based

        // Build 3-line context (contextLines defaults to 3 from rule)
        int contextRadius = Math.max(1, rule.contextLines / 2);
        if (contextRadius > 3) contextRadius = 3;
        List<String> context = new ArrayList<>();
        int start = Math.max(0, matchLineIdx - contextRadius);
        int end = Math.min(lines.length - 1, matchLineIdx + contextRadius);
        for (int i = start; i <= end; i++) {
            String prefix = (i == matchLineIdx) ?
                    (i + 1) + ": >>> " :
                    (i + 1) + ":     ";
            context.add(prefix + lines[i].trim());
        }
        match.contextLines = context;
    }

    private void extractClassInfo(String code, Set<String> interfaces) {
        if (code == null) return;
        // Simple regex-free extraction
        int implIdx = code.indexOf(" implements ");
        if (implIdx > 0) {
            int braceIdx = code.indexOf('{', implIdx);
            if (braceIdx > implIdx) {
                String implStr = code.substring(implIdx + 12, braceIdx).trim();
                for (String iface : implStr.split(",")) {
                    interfaces.add(iface.trim());
                }
            }
        }
    }

    private void updateSeverityCounts() {
        int c = 0, h = 0, m = 0, i = 0;
        for (RuleMatch match : allFindings) {
            switch (match.severity) {
                case "critical": c++; break;
                case "high": h++; break;
                case "medium": m++; break;
                default: i++; break;
            }
        }
        criticalCount = c;
        highCount = h;
        mediumCount = m;
        infoCount = i;
    }

    private int severityLevel(String severity) {
        if (severity == null) return 0;
        switch (severity.toLowerCase()) {
            case "critical": return 4;
            case "high": return 3;
            case "medium": return 2;
            case "info": return 1;
            default: return 0;
        }
    }

    // ==================== Inner types ====================

    public static class RuleMatch {
        public String ruleId;
        public String ruleName;
        public String severity;
        public String category;
        public String description;
        public String remediation;
        public List<String> tags;
        public String className;
        public String methodName;
        public int lineNumber = -1;
        public String matchType;       // method_invoke, string_contains, class_inherit, etc.
        public String confidence = "high";
        public List<String> contextLines;  // 3-line context array per spec

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rule_id", ruleId);
            map.put("severity", severity);
            map.put("category", category);
            map.put("class_name", className);
            if (!"*".equals(methodName)) {
                map.put("method_name", methodName);
            }
            if (lineNumber >= 0) {
                map.put("line_number", lineNumber);
            }
            map.put("description", description);
            if (contextLines != null && !contextLines.isEmpty()) {
                map.put("context", contextLines);
            }
            if (tags != null && !tags.isEmpty()) {
                map.put("tags", tags);
            }
            map.put("match_type", matchType != null ? matchType : "unknown");
            map.put("confidence", confidence);
            if (remediation != null) {
                map.put("remediation", remediation);
            }
            return map;
        }
    }

    public static class ScanResult {
        public int totalFindings;
        public int criticalCount;
        public int highCount;
        public int mediumCount;
        public int infoCount;
        public int rulesEvaluated;
        public int classesScanned;
        public long elapsedMs;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total_findings", totalFindings);
            map.put("critical", criticalCount);
            map.put("high", highCount);
            map.put("medium", mediumCount);
            map.put("info", infoCount);
            map.put("rules_evaluated", rulesEvaluated);
            map.put("classes_scanned", classesScanned);
            map.put("elapsed_ms", elapsedMs);
            return map;
        }
    }
}
