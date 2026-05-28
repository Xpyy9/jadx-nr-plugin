package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 1: 字符串常量倒排索引。
 * 在 CodeIndex 遍历时提取所有 string literals，构建 value → List<StringRef> 倒排索引。
 *
 * 对外暴露:
 * - /search?action=find&scope=string 使用预构建索引（毫秒级）
 * - /search?action=find&scope=url 只返回 URL 类字符串
 * - /search?action=find&scope=secret 只返回疑似密钥/token 的字符串
 */
public class StringConstantIndex {
    private static final Logger LOG = LoggerFactory.getLogger(StringConstantIndex.class);

    // string_value → List<StringRef> inverted index
    private final ConcurrentHashMap<String, List<StringRef>> index = new ConcurrentHashMap<>();

    private final AtomicInteger totalStrings = new AtomicInteger(0);

    // Pattern to extract string literals from decompiled source
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");

    // URL pattern
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\"\\s]+");

    // Possible secret pattern: base64-like, hex-like, long alphanumeric
    private static final Pattern SECRET_PATTERN = Pattern.compile("[A-Za-z0-9+/=_\\-]{16,}");

    // Skip known non-interesting strings
    private static final Set<String> SKIP_VALUES = Set.of(
            "", " ", ",", ".", ":", ";", "/", "\\", "\n", "\t",
            "null", "true", "false", "UTF-8", "utf-8", "US-ASCII",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD",
            "Content-Type", "application/json"
    );

    // ==================== Build API ====================

    /**
     * Scan a class source code for string literals.
     * Called during CodeIndex traversal.
     */
    public void scanClass(String className, String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return;

        Matcher matcher = STRING_LITERAL.matcher(sourceCode);
        int lineNum = 1;
        int lastLineStart = 0;

        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null || value.length() < 2 || SKIP_VALUES.contains(value)) continue;

            // Compute line number
            int pos = matcher.start();
            for (int i = lastLineStart; i < pos && i < sourceCode.length(); i++) {
                if (sourceCode.charAt(i) == '\n') {
                    lineNum++;
                    lastLineStart = i + 1;
                }
            }

            // Find enclosing method (heuristic: last method decl before this position)
            String methodName = findEnclosingMethod(sourceCode, pos);

            StringRef ref = new StringRef(className, methodName, lineNum, value);
            index.computeIfAbsent(value, k -> Collections.synchronizedList(new ArrayList<>())).add(ref);
            totalStrings.incrementAndGet();
        }
    }

    // ==================== Query API ====================

    public enum MatchMode {
        EXACT,      // exact match
        CONTAINS,   // substring match
        PREFIX      // prefix match
    }

    /**
     * Search string constants by pattern and mode.
     */
    public List<StringRef> search(String pattern, MatchMode mode) {
        if (pattern == null || pattern.isEmpty()) return Collections.emptyList();

        List<StringRef> results = new ArrayList<>();
        String lowerPattern = pattern.toLowerCase();

        for (Map.Entry<String, List<StringRef>> entry : index.entrySet()) {
            String key = entry.getKey();
            boolean match = false;
            switch (mode) {
                case EXACT:
                    match = key.equals(pattern);
                    break;
                case CONTAINS:
                    match = key.toLowerCase().contains(lowerPattern);
                    break;
                case PREFIX:
                    match = key.toLowerCase().startsWith(lowerPattern);
                    break;
            }
            if (match) {
                results.addAll(entry.getValue());
            }
            if (results.size() >= 200) break; // hard cap
        }
        return results;
    }

    /**
     * Find all URL-type string constants.
     */
    public List<StringRef> findUrls() {
        List<StringRef> results = new ArrayList<>();
        for (Map.Entry<String, List<StringRef>> entry : index.entrySet()) {
            if (URL_PATTERN.matcher(entry.getKey()).find()) {
                results.addAll(entry.getValue());
            }
            if (results.size() >= 200) break;
        }
        return results;
    }

    /**
     * Find possible secrets: strings >= 16 chars that look like base64, hex, or API keys.
     */
    public List<StringRef> findPossibleSecrets() {
        List<StringRef> results = new ArrayList<>();
        for (Map.Entry<String, List<StringRef>> entry : index.entrySet()) {
            String val = entry.getKey();
            if (val.length() >= 16 && SECRET_PATTERN.matcher(val).matches()) {
                // Skip known non-secrets (common base64 encoded standard strings)
                if (isLikelySecret(val)) {
                    results.addAll(entry.getValue());
                }
            }
            if (results.size() >= 100) break;
        }
        return results;
    }

    public int getTotalStrings() {
        return totalStrings.get();
    }

    public int getUniqueCount() {
        return index.size();
    }

    public void clear() {
        index.clear();
        totalStrings.set(0);
    }

    // ==================== Result type ====================

    /**
     * Convert search results to maps for JSON serialization.
     */
    public List<Map<String, Object>> toMaps(List<StringRef> refs) {
        // Group by class for compact output
        Map<String, List<StringRef>> byClass = new LinkedHashMap<>();
        for (StringRef ref : refs) {
            byClass.computeIfAbsent(ref.className, k -> new ArrayList<>()).add(ref);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, List<StringRef>> entry : byClass.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("class_name", entry.getKey());
            item.put("match_type", "string_constant");
            item.put("is_third_party", false);

            List<Map<String, Object>> strings = new ArrayList<>();
            for (StringRef ref : entry.getValue()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("value", ref.value.length() > 100 ? ref.value.substring(0, 100) + "..." : ref.value);
                s.put("method", ref.methodName);
                s.put("line", ref.lineNumber);
                strings.add(s);
            }
            item.put("strings", strings);
            results.add(item);
        }
        return results;
    }

    // ==================== Internal ====================

    private boolean isLikelySecret(String value) {
        // Skip if it's all the same character repeated
        if (value.chars().distinct().count() < 4) return false;
        // Skip common Android resource identifiers
        if (value.startsWith("com.") || value.startsWith("android.") || value.startsWith("org.")) return false;
        // Skip if it looks like a package name
        if (value.contains(".") && !value.contains("/") && !value.contains("+")) return false;
        return true;
    }

    private String findEnclosingMethod(String source, int position) {
        // Search backwards for the most recent method declaration
        Pattern methodDecl = Pattern.compile(
                "(?:public|private|protected)?\\s*(?:static)?\\s*(?:abstract)?\\s*" +
                        "(?:[\\w<>\\[\\]?,\\s]+)\\s+(\\w+)\\s*\\(");
        int searchStart = Math.max(0, position - 500);
        String snippet = source.substring(searchStart, position);
        Matcher m = methodDecl.matcher(snippet);
        String lastMethod = "<init>";
        while (m.find()) {
            lastMethod = m.group(1);
        }
        return lastMethod;
    }

    // ==================== Inner type ====================

    public static class StringRef {
        public final String className;
        public final String methodName;
        public final int lineNumber;
        public final String value;

        public StringRef(String className, String methodName, int lineNumber, String value) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
            this.value = value;
        }
    }
}
