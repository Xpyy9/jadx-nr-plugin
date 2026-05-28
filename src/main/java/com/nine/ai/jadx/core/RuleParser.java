package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Layer 3: YAML 安全规则解析器。
 * 解析 YAML 规则文件为 SecurityRule 对象列表。
 *
 * 规则文件格式:
 * version: "1.0"
 * category: "crypto"
 * rules:
 *   - id: weak_crypto_des
 *     severity: high
 *     name: "DES 弱加密算法"
 *     match:
 *       type: method_invoke
 *       target: "javax.crypto.Cipher.getInstance"
 *       args_contain: ["DES"]
 */
public class RuleParser {
    private static final Logger LOG = LoggerFactory.getLogger(RuleParser.class);

    /**
     * Parse all YAML rule files from a directory.
     */
    public List<SecurityRule> parseDirectory(Path rulesDir) {
        List<SecurityRule> allRules = new ArrayList<>();

        if (rulesDir == null || !Files.isDirectory(rulesDir)) {
            LOG.warn("Rules directory not found: {}", rulesDir);
            return allRules;
        }

        try (var stream = Files.list(rulesDir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            List<SecurityRule> rules = parseFile(path);
                            allRules.addAll(rules);
                            LOG.info("Loaded {} rules from {}", rules.size(), path.getFileName());
                        } catch (Exception e) {
                            LOG.error("Failed to parse rule file: {}", path, e);
                        }
                    });
        } catch (Exception e) {
            LOG.error("Failed to list rules directory: {}", rulesDir, e);
        }

        return allRules;
    }

    /**
     * Parse rules from classpath resources (bundled rules).
     */
    public List<SecurityRule> parseResource(String resourcePath) {
        List<SecurityRule> rules = new ArrayList<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Rule resource not found: {}", resourcePath);
                return rules;
            }
            rules = parseYaml(is, resourcePath);
        } catch (Exception e) {
            LOG.error("Failed to parse rule resource: {}", resourcePath, e);
        }
        return rules;
    }

    /**
     * Parse a single YAML rule file.
     */
    @SuppressWarnings("unchecked")
    public List<SecurityRule> parseFile(Path path) throws Exception {
        try (InputStream is = Files.newInputStream(path)) {
            return parseYaml(is, path.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private List<SecurityRule> parseYaml(InputStream is, String source) {
        List<SecurityRule> result = new ArrayList<>();
        Yaml yaml = new Yaml();
        Map<String, Object> doc = yaml.load(is);

        if (doc == null) return result;

        String category = (String) doc.getOrDefault("category", "unknown");
        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) doc.get("rules");
        if (rulesList == null) return result;

        for (Map<String, Object> ruleDef : rulesList) {
            try {
                SecurityRule rule = parseRule(ruleDef, category);
                if (rule != null) {
                    result.add(rule);
                }
            } catch (Exception e) {
                LOG.warn("Skipping invalid rule in {}: {}", source, e.getMessage());
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private SecurityRule parseRule(Map<String, Object> def, String defaultCategory) {
        SecurityRule rule = new SecurityRule();
        rule.id = (String) def.get("id");
        rule.severity = (String) def.getOrDefault("severity", "info");
        rule.name = (String) def.getOrDefault("name", rule.id);
        rule.description = (String) def.getOrDefault("description", "");
        rule.category = (String) def.getOrDefault("category", defaultCategory);
        rule.remediation = (String) def.get("remediation");
        rule.contextLines = def.containsKey("context_lines") ?
                ((Number) def.get("context_lines")).intValue() : 3;

        // Tags
        List<String> tags = (List<String>) def.get("tags");
        rule.tags = tags != null ? tags : Collections.emptyList();

        // Match specification
        Map<String, Object> matchDef = (Map<String, Object>) def.get("match");
        if (matchDef == null) {
            LOG.warn("Rule {} has no match specification", rule.id);
            return null;
        }

        rule.matcher = parseMatcher(matchDef);
        if (rule.matcher == null) {
            LOG.warn("Rule {} has invalid match specification", rule.id);
            return null;
        }

        return rule;
    }

    @SuppressWarnings("unchecked")
    private RuleMatcher parseMatcher(Map<String, Object> matchDef) {
        String type = (String) matchDef.get("type");
        if (type == null) {
            // Shorthand: if it has "method_invoke" directly
            if (matchDef.containsKey("method_invoke")) {
                return new MethodInvokeMatcher(
                        (String) matchDef.get("method_invoke"),
                        (List<String>) matchDef.get("args_contain")
                );
            }
            if (matchDef.containsKey("string_contains")) {
                return new StringContainsMatcher((String) matchDef.get("string_contains"));
            }
            if (matchDef.containsKey("class_inherit")) {
                return new ClassInheritMatcher((String) matchDef.get("class_inherit"));
            }
            return null;
        }

        switch (type) {
            case "method_invoke":
                return new MethodInvokeMatcher(
                        (String) matchDef.get("target"),
                        (List<String>) matchDef.get("args_contain")
                );

            case "string_contains":
                return new StringContainsMatcher(
                        (String) matchDef.get("value")
                );

            case "class_inherit":
                String iface = (String) matchDef.get("implements");
                String ext = (String) matchDef.get("extends");
                return new ClassInheritMatcher(iface != null ? iface : ext);

            case "annotation":
                return new AnnotationMatcher((String) matchDef.get("name"));

            case "has_literal_arg":
                return new LiteralArgMatcher();

            case "manifest_check":
                return new ManifestCheckMatcher(
                        (String) matchDef.get("component_type"),
                        Boolean.TRUE.equals(matchDef.get("exported")),
                        Boolean.TRUE.equals(matchDef.get("no_permission"))
                );

            case "pattern":
                // Composite matcher (all/any/not)
                if (matchDef.containsKey("all")) {
                    List<Map<String, Object>> allDefs = (List<Map<String, Object>>) matchDef.get("all");
                    List<RuleMatcher> matchers = new ArrayList<>();
                    for (Map<String, Object> subDef : allDefs) {
                        RuleMatcher sub = parseMatcher(subDef);
                        if (sub != null) matchers.add(sub);
                    }
                    return new CompositeMatcher(CompositeMatcher.Op.ALL, matchers);
                }
                if (matchDef.containsKey("any")) {
                    List<Map<String, Object>> anyDefs = (List<Map<String, Object>>) matchDef.get("any");
                    List<RuleMatcher> matchers = new ArrayList<>();
                    for (Map<String, Object> subDef : anyDefs) {
                        RuleMatcher sub = parseMatcher(subDef);
                        if (sub != null) matchers.add(sub);
                    }
                    return new CompositeMatcher(CompositeMatcher.Op.ANY, matchers);
                }
                if (matchDef.containsKey("not_contains")) {
                    return new NegationMatcher(
                            new StringContainsMatcher((String) matchDef.get("not_contains"))
                    );
                }
                return null;

            default:
                LOG.warn("Unknown match type: {}", type);
                return null;
        }
    }

    // ==================== Matcher types ====================

    public interface RuleMatcher {
        boolean matches(MatchContext ctx);
        String describe();
    }

    /**
     * Context passed to matchers during evaluation.
     */
    public static class MatchContext {
        public final String className;
        public final String methodName;
        public final String sourceCode;
        public final Set<String> invokedMethods;      // normalized method targets in this method
        public final Set<String> implementedInterfaces; // interfaces this class implements
        public final Set<String> annotations;           // annotations on this class/method
        public final String superClass;

        // Manifest-aware fields (populated for manifest_check rules)
        public final String manifestComponentType;  // activity, service, receiver, provider
        public final boolean manifestExported;
        public final boolean manifestHasPermission;

        public MatchContext(String className, String methodName, String sourceCode,
                            Set<String> invokedMethods, Set<String> implementedInterfaces,
                            Set<String> annotations, String superClass) {
            this(className, methodName, sourceCode, invokedMethods, implementedInterfaces,
                    annotations, superClass, null, false, false);
        }

        public MatchContext(String className, String methodName, String sourceCode,
                            Set<String> invokedMethods, Set<String> implementedInterfaces,
                            Set<String> annotations, String superClass,
                            String manifestComponentType, boolean manifestExported, boolean manifestHasPermission) {
            this.className = className;
            this.methodName = methodName;
            this.sourceCode = sourceCode;
            this.invokedMethods = invokedMethods != null ? invokedMethods : Collections.emptySet();
            this.implementedInterfaces = implementedInterfaces != null ? implementedInterfaces : Collections.emptySet();
            this.annotations = annotations != null ? annotations : Collections.emptySet();
            this.superClass = superClass;
            this.manifestComponentType = manifestComponentType;
            this.manifestExported = manifestExported;
            this.manifestHasPermission = manifestHasPermission;
        }
    }

    // --- Concrete matchers ---

    public static class MethodInvokeMatcher implements RuleMatcher {
        private final String target;
        private final List<String> argsContain;

        public MethodInvokeMatcher(String target, List<String> argsContain) {
            this.target = target;
            this.argsContain = argsContain;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            if (target == null) return false;
            // Check if any invoked method matches the target
            for (String invoked : ctx.invokedMethods) {
                if (invoked.contains(target) || target.contains(invoked)) {
                    // If args_contain specified, check source code for those strings
                    if (argsContain != null && !argsContain.isEmpty()) {
                        for (String arg : argsContain) {
                            if (ctx.sourceCode != null && ctx.sourceCode.contains(arg)) {
                                return true;
                            }
                        }
                        return false;
                    }
                    return true;
                }
            }
            // Fallback: text search in source code for the target API
            if (ctx.sourceCode != null) {
                String simpleName = target.substring(target.lastIndexOf('.') + 1);
                if (ctx.sourceCode.contains(simpleName)) {
                    if (argsContain != null && !argsContain.isEmpty()) {
                        for (String arg : argsContain) {
                            if (ctx.sourceCode.contains(arg)) return true;
                        }
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return "method_invoke:" + target + (argsContain != null ? " args:" + argsContain : "");
        }
    }

    public static class StringContainsMatcher implements RuleMatcher {
        private final String value;

        public StringContainsMatcher(String value) {
            this.value = value;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            return value != null && ctx.sourceCode != null && ctx.sourceCode.contains(value);
        }

        @Override
        public String describe() {
            return "string_contains:" + value;
        }
    }

    public static class ClassInheritMatcher implements RuleMatcher {
        private final String target;

        public ClassInheritMatcher(String target) {
            this.target = target;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            if (target == null) return false;
            // Check interfaces
            for (String iface : ctx.implementedInterfaces) {
                if (iface.contains(target) || target.contains(iface)) return true;
            }
            // Check superclass
            if (ctx.superClass != null && (ctx.superClass.contains(target) || target.contains(ctx.superClass))) {
                return true;
            }
            // Fallback: text search
            return ctx.sourceCode != null && (
                    ctx.sourceCode.contains("implements " + simpleNameOf(target)) ||
                    ctx.sourceCode.contains("extends " + simpleNameOf(target))
            );
        }

        private String simpleNameOf(String fullName) {
            int dot = fullName.lastIndexOf('.');
            return dot > 0 ? fullName.substring(dot + 1) : fullName;
        }

        @Override
        public String describe() {
            return "class_inherit:" + target;
        }
    }

    public static class AnnotationMatcher implements RuleMatcher {
        private final String annotationName;

        public AnnotationMatcher(String annotationName) {
            this.annotationName = annotationName;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            if (annotationName == null) return false;
            for (String ann : ctx.annotations) {
                if (ann.contains(annotationName)) return true;
            }
            // Fallback
            return ctx.sourceCode != null && ctx.sourceCode.contains("@" + annotationName);
        }

        @Override
        public String describe() {
            return "annotation:" + annotationName;
        }
    }

    public static class LiteralArgMatcher implements RuleMatcher {
        @Override
        public boolean matches(MatchContext ctx) {
            // Heuristic: look for byte array literals or string literals near crypto APIs
            if (ctx.sourceCode == null) return false;
            return ctx.sourceCode.contains("new byte[]") ||
                    ctx.sourceCode.contains("getBytes(") ||
                    ctx.sourceCode.matches("(?s).*SecretKeySpec\\s*\\(\\s*\"[^\"]+\".*");
        }

        @Override
        public String describe() {
            return "has_literal_arg";
        }
    }

    public static class ManifestCheckMatcher implements RuleMatcher {
        private final String componentType;
        private final boolean exported;
        private final boolean noPermission;

        public ManifestCheckMatcher(String componentType, boolean exported, boolean noPermission) {
            this.componentType = componentType;
            this.exported = exported;
            this.noPermission = noPermission;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            // Only match when manifest data is present in context
            if (ctx.manifestComponentType == null) return false;

            // Check component type (null or empty = any type)
            if (componentType != null && !componentType.isEmpty()) {
                if (!componentType.equalsIgnoreCase(ctx.manifestComponentType)) return false;
            }

            // Check exported condition
            if (exported && !ctx.manifestExported) return false;

            // Check no_permission condition
            if (noPermission && ctx.manifestHasPermission) return false;

            return true;
        }

        public String getComponentType() { return componentType; }
        public boolean isExported() { return exported; }
        public boolean isNoPermission() { return noPermission; }

        @Override
        public String describe() {
            return "manifest_check:" + componentType + " exported=" + exported + " no_perm=" + noPermission;
        }
    }

    public static class CompositeMatcher implements RuleMatcher {
        public enum Op { ALL, ANY }
        private final Op op;
        private final List<RuleMatcher> children;

        public CompositeMatcher(Op op, List<RuleMatcher> children) {
            this.op = op;
            this.children = children;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            if (children.isEmpty()) return false;
            if (op == Op.ALL) {
                return children.stream().allMatch(m -> m.matches(ctx));
            } else {
                return children.stream().anyMatch(m -> m.matches(ctx));
            }
        }

        @Override
        public String describe() {
            return op.name() + "(" + children.size() + " matchers)";
        }
    }

    public static class NegationMatcher implements RuleMatcher {
        private final RuleMatcher inner;

        public NegationMatcher(RuleMatcher inner) {
            this.inner = inner;
        }

        @Override
        public boolean matches(MatchContext ctx) {
            return !inner.matches(ctx);
        }

        @Override
        public String describe() {
            return "NOT(" + inner.describe() + ")";
        }
    }

    // ==================== Data types ====================

    public static class SecurityRule {
        public String id;
        public String severity;   // critical, high, medium, info
        public String name;
        public String description;
        public String category;
        public String remediation;
        public int contextLines = 3;
        public List<String> tags = new ArrayList<>();
        public RuleMatcher matcher;
    }
}
