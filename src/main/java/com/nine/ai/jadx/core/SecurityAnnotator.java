package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2: Sink/Source 安全标注器。
 * 在 CodeIndex 遍历时对每个方法的 InvokeNode 做 API 匹配，标注该方法包含的 sink/source 调用。
 *
 * 标注结果缓存在内存中，供 CodeHandler/SearchHandler/AnalyzeHandler 查询。
 */
public class SecurityAnnotator {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityAnnotator.class);

    // methodKey → SecurityTag
    private final ConcurrentHashMap<String, SecurityTag> annotations = new ConcurrentHashMap<>();

    // class → ClassSecuritySummary
    private final ConcurrentHashMap<String, ClassSecuritySummary> classSummaries = new ConcurrentHashMap<>();

    // Counters
    private volatile int totalSinks = 0;
    private volatile int totalSources = 0;

    // ==================== Sink API Registry ====================

    private static final Map<String, String> SINK_APIS = new HashMap<>();
    static {
        // crypto
        SINK_APIS.put("javax.crypto.Cipher.getInstance", "crypto");
        SINK_APIS.put("javax.crypto.Cipher.init", "crypto");
        SINK_APIS.put("javax.crypto.spec.SecretKeySpec.<init>", "crypto");
        SINK_APIS.put("java.security.MessageDigest.getInstance", "crypto");
        SINK_APIS.put("javax.crypto.Mac.getInstance", "crypto");
        SINK_APIS.put("javax.crypto.KeyGenerator.getInstance", "crypto");

        // webview
        SINK_APIS.put("android.webkit.WebView.loadUrl", "webview");
        SINK_APIS.put("android.webkit.WebView.loadData", "webview");
        SINK_APIS.put("android.webkit.WebView.loadDataWithBaseURL", "webview");
        SINK_APIS.put("android.webkit.WebView.addJavascriptInterface", "webview");
        SINK_APIS.put("android.webkit.WebView.evaluateJavascript", "webview");
        SINK_APIS.put("android.webkit.WebSettings.setJavaScriptEnabled", "webview");
        SINK_APIS.put("android.webkit.WebSettings.setAllowFileAccess", "webview");
        SINK_APIS.put("android.webkit.WebSettings.setAllowUniversalAccessFromFileURLs", "webview");

        // exec
        SINK_APIS.put("java.lang.Runtime.exec", "exec");
        SINK_APIS.put("java.lang.ProcessBuilder.start", "exec");
        SINK_APIS.put("java.lang.ProcessBuilder.<init>", "exec");

        // sql
        SINK_APIS.put("android.database.sqlite.SQLiteDatabase.rawQuery", "sql");
        SINK_APIS.put("android.database.sqlite.SQLiteDatabase.execSQL", "sql");
        SINK_APIS.put("android.database.sqlite.SQLiteDatabase.query", "sql");
        SINK_APIS.put("android.database.sqlite.SQLiteDatabase.compileStatement", "sql");

        // file
        SINK_APIS.put("java.io.FileOutputStream.<init>", "file");
        SINK_APIS.put("android.content.Context.openFileOutput", "file");
        SINK_APIS.put("android.os.Environment.getExternalStorageDirectory", "file");
        SINK_APIS.put("java.io.File.<init>", "file");

        // intent
        SINK_APIS.put("android.content.Context.sendBroadcast", "intent");
        SINK_APIS.put("android.content.Context.sendOrderedBroadcast", "intent");
        SINK_APIS.put("android.content.Context.startActivity", "intent");
        SINK_APIS.put("android.content.Context.startService", "intent");
        SINK_APIS.put("android.app.PendingIntent.getActivity", "intent");
        SINK_APIS.put("android.app.PendingIntent.getBroadcast", "intent");

        // network
        SINK_APIS.put("java.net.HttpURLConnection.connect", "network");
        SINK_APIS.put("java.net.URL.openConnection", "network");
        SINK_APIS.put("okhttp3.Call.execute", "network");
        SINK_APIS.put("okhttp3.Call.enqueue", "network");
        SINK_APIS.put("okhttp3.OkHttpClient.newCall", "network");

        // dynamic_code
        SINK_APIS.put("dalvik.system.DexClassLoader.<init>", "dynamic_code");
        SINK_APIS.put("dalvik.system.PathClassLoader.<init>", "dynamic_code");
        SINK_APIS.put("dalvik.system.InMemoryDexClassLoader.<init>", "dynamic_code");
        SINK_APIS.put("java.lang.reflect.Method.invoke", "dynamic_code");
        SINK_APIS.put("java.lang.Class.forName", "dynamic_code");

        // log (data leak)
        SINK_APIS.put("android.util.Log.d", "log");
        SINK_APIS.put("android.util.Log.i", "log");
        SINK_APIS.put("android.util.Log.e", "log");
        SINK_APIS.put("android.util.Log.v", "log");
        SINK_APIS.put("android.util.Log.w", "log");
    }

    // ==================== Source API Registry ====================

    private static final Map<String, String> SOURCE_APIS = new HashMap<>();
    static {
        // intent
        SOURCE_APIS.put("android.content.Intent.getStringExtra", "intent");
        SOURCE_APIS.put("android.content.Intent.getIntExtra", "intent");
        SOURCE_APIS.put("android.content.Intent.getBooleanExtra", "intent");
        SOURCE_APIS.put("android.content.Intent.getData", "intent");
        SOURCE_APIS.put("android.content.Intent.getExtras", "intent");
        SOURCE_APIS.put("android.content.Intent.getSerializableExtra", "intent");
        SOURCE_APIS.put("android.content.Intent.getParcelableExtra", "intent");
        SOURCE_APIS.put("android.app.Activity.getIntent", "intent");

        // deeplink
        SOURCE_APIS.put("android.net.Uri.getQueryParameter", "deeplink");
        SOURCE_APIS.put("android.net.Uri.getPath", "deeplink");
        SOURCE_APIS.put("android.net.Uri.getHost", "deeplink");
        SOURCE_APIS.put("android.net.Uri.getScheme", "deeplink");
        SOURCE_APIS.put("android.net.Uri.getLastPathSegment", "deeplink");

        // network
        SOURCE_APIS.put("java.io.InputStream.read", "network");
        SOURCE_APIS.put("okhttp3.Response.body", "network");
        SOURCE_APIS.put("okhttp3.ResponseBody.string", "network");
        SOURCE_APIS.put("java.io.BufferedReader.readLine", "network");

        // file
        SOURCE_APIS.put("java.io.FileInputStream.<init>", "file");
        SOURCE_APIS.put("android.content.Context.openFileInput", "file");
        SOURCE_APIS.put("android.content.ContentResolver.query", "file");

        // clipboard
        SOURCE_APIS.put("android.content.ClipboardManager.getPrimaryClip", "clipboard");

        // shared_preferences
        SOURCE_APIS.put("android.content.SharedPreferences.getString", "shared_prefs");
        SOURCE_APIS.put("android.content.SharedPreferences.getInt", "shared_prefs");
        SOURCE_APIS.put("android.content.SharedPreferences.getBoolean", "shared_prefs");

        // user_input
        SOURCE_APIS.put("android.widget.EditText.getText", "user_input");
    }

    // ==================== Protocol Field Patterns ====================

    /**
     * Protocol field detection patterns for sign/token/encrypt/timestamp fields.
     * Used by findProtocolFields() for AST-level detection from annotations.
     */
    private static final List<ProtocolFieldPattern> PROTOCOL_FIELD_PATTERNS = List.of(
        // Sign/signature fields
        new ProtocolFieldPattern("sign", Pattern.compile("(?i)(x-sign|signature|x-signature|hmac)"), "header"),
        new ProtocolFieldPattern("sign", Pattern.compile("(?i)(sign|signature)"), "query"),
        // Token fields
        new ProtocolFieldPattern("token", Pattern.compile("(?i)(authorization|auth-token|x-token|bearer)"), "header"),
        new ProtocolFieldPattern("token", Pattern.compile("(?i)(token|access_token|refresh_token|session_id)"), "query"),
        // Encryption fields
        new ProtocolFieldPattern("encrypt_data", Pattern.compile("(?i)(encrypt|cipher|encrypted_data|encryptdata)"), "query"),
        new ProtocolFieldPattern("encrypt_data", Pattern.compile("(?i)(x-encrypted|x-cipher)"), "header"),
        // Timestamp/nonce
        new ProtocolFieldPattern("timestamp", Pattern.compile("(?i)(timestamp|ts)"), "query"),
        new ProtocolFieldPattern("nonce", Pattern.compile("(?i)(nonce|random|iv)"), "query"),
        // Device/session
        new ProtocolFieldPattern("device_id", Pattern.compile("(?i)(device_id|deviceid|udid)"), "query"),
        new ProtocolFieldPattern("session", Pattern.compile("(?i)(session|sid|jsessionid)"), "cookie")
    );

    record ProtocolFieldPattern(String fieldRole, Pattern namePattern, String location) {}

    // ==================== Annotation API ====================

    /**
     * Annotate a method based on an InvokeNode target.
     * Called during CodeIndex traversal for each InvokeNode found.
     *
     * @param methodKey   The method containing the invoke (format: "pkg.Class#method(sig)V")
     * @param callTarget  The full ID of the called method
     */
    public void recordInvoke(String methodKey, String className, String callTarget) {
        String sinkCategory = matchAPI(callTarget, SINK_APIS);
        String sourceCategory = matchAPI(callTarget, SOURCE_APIS);

        if (sinkCategory == null && sourceCategory == null) return;

        SecurityTag tag = annotations.computeIfAbsent(methodKey, k -> new SecurityTag());
        if (sinkCategory != null) {
            tag.addSink(sinkCategory, callTarget);
        }
        if (sourceCategory != null) {
            tag.addSource(sourceCategory, callTarget);
        }
    }

    /**
     * After all methods in a class are annotated, build the class summary.
     */
    public void finalizeClass(String className, List<String> methodKeys) {
        ClassSecuritySummary summary = new ClassSecuritySummary();
        for (String key : methodKeys) {
            SecurityTag tag = annotations.get(key);
            if (tag == null) continue;
            summary.totalSinks += tag.getSinkCount();
            summary.totalSources += tag.getSourceCount();
            summary.sinkCategories.addAll(tag.getSinkCategories());
            summary.sourceCategories.addAll(tag.getSourceCategories());
            summary.protocolFields.addAll(tag.getProtocolFields());
        }
        if (summary.totalSinks > 0 || summary.totalSources > 0 || !summary.protocolFields.isEmpty()) {
            summary.riskLevel = computeRiskLevel(summary);
            classSummaries.put(className, summary);
        }
    }

    /**
     * Called after full index build to compute totals.
     */
    public void finalizeBuild() {
        int sinks = 0, sources = 0;
        for (SecurityTag tag : annotations.values()) {
            if (tag.hasSinks()) sinks++;
            if (tag.hasSources()) sources++;
        }
        this.totalSinks = sinks;
        this.totalSources = sources;
        LOG.info("SecurityAnnotator: {} methods with sinks, {} methods with sources", sinks, sources);
    }

    // ==================== Query API ====================

    public SecurityTag getTag(String methodKey) {
        return annotations.get(methodKey);
    }

    public ClassSecuritySummary getClassSummary(String className) {
        return classSummaries.get(className);
    }

    public int getTotalSinks() { return totalSinks; }
    public int getTotalSources() { return totalSources; }

    /**
     * Get all methods that are sinks in a given category.
     * Enhanced format per spec: includes caller_count, is_reachable_from_entry, specific_api.
     */
    public List<Map<String, Object>> findSinks(String categoryFilter) {
        return findSinks(categoryFilter, null);
    }

    /**
     * Get all methods that are sinks in a given category, enriched with call graph data.
     */
    public List<Map<String, Object>> findSinks(String categoryFilter, CallGraph callGraph) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, SecurityTag> entry : annotations.entrySet()) {
            SecurityTag tag = entry.getValue();
            if (!tag.hasSinks()) continue;
            if (categoryFilter != null && !tag.getSinkCategories().contains(categoryFilter)) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            String[] parts = parseMethodKey(entry.getKey());
            item.put("class_name", parts[0]);
            item.put("class", parts[0]); // Go extractor compat
            item.put("method_name", parts[1]);
            item.put("method", parts[1]); // Go extractor compat
            item.put("signature", parts[1] + (parts.length > 2 ? parts[2] : ""));
            item.put("is_sink", true); // Go extractor compat
            item.put("category", tag.getSinkCategories().iterator().next());
            item.put("specific_api", !tag.getSinkApis().isEmpty() ? normalizeMethodId(tag.getSinkApis().get(0)) : "");

            // Call graph enrichment
            if (callGraph != null) {
                Set<String> callers = callGraph.getCallers(entry.getKey());
                item.put("caller_count", callers != null ? callers.size() : 0);
                item.put("is_reachable_from_entry", callGraph.isEntryPoint(entry.getKey()) ||
                        (callers != null && callers.stream().anyMatch(callGraph::isEntryPoint)));
            } else {
                item.put("caller_count", 0);
                item.put("is_reachable_from_entry", false);
            }

            results.add(item);
        }
        return results;
    }

    /**
     * Get all methods that are sources in a given category.
     * Enhanced format per spec: includes caller_count, is_reachable_from_entry, specific_api.
     */
    public List<Map<String, Object>> findSources(String categoryFilter) {
        return findSources(categoryFilter, null);
    }

    /**
     * Get all methods that are sources in a given category, enriched with call graph data.
     */
    public List<Map<String, Object>> findSources(String categoryFilter, CallGraph callGraph) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, SecurityTag> entry : annotations.entrySet()) {
            SecurityTag tag = entry.getValue();
            if (!tag.hasSources()) continue;
            if (categoryFilter != null && !tag.getSourceCategories().contains(categoryFilter)) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            String[] parts = parseMethodKey(entry.getKey());
            item.put("class_name", parts[0]);
            item.put("class", parts[0]); // Go extractor compat
            item.put("method_name", parts[1]);
            item.put("method", parts[1]); // Go extractor compat
            item.put("signature", parts[1] + (parts.length > 2 ? parts[2] : ""));
            item.put("is_source", true); // Go extractor compat
            item.put("category", tag.getSourceCategories().iterator().next());
            item.put("specific_api", !tag.getSourceApis().isEmpty() ? normalizeMethodId(tag.getSourceApis().get(0)) : "");

            // Call graph enrichment
            if (callGraph != null) {
                Set<String> callers = callGraph.getCallers(entry.getKey());
                item.put("caller_count", callers != null ? callers.size() : 0);
                item.put("is_reachable_from_entry", callGraph.isEntryPoint(entry.getKey()) ||
                        (callers != null && callers.stream().anyMatch(callGraph::isEntryPoint)));
            } else {
                item.put("caller_count", 0);
                item.put("is_reachable_from_entry", false);
            }

            results.add(item);
        }
        return results;
    }

    /**
     * Detect protocol fields from method annotations (AST-level).
     * Matches @Header/@Query/@Field/@Headers against PROTOCOL_FIELD_PATTERNS.
     * Returns list of protocol field maps with confidence 0.9 (higher than agent regex).
     */
    public List<Map<String, Object>> findProtocolFields(String className, String methodName,
                                                         List<?> annotations, List<?> parameterAnnotations) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (annotations == null) return fields;

        // 1. Check method annotations (@Header, @Query, @Field)
        for (Object annObj : annotations) {
            String annStr = annObj.toString();
            String location = null;
            if (annStr.contains("retrofit2/http/Header")) location = "header";
            else if (annStr.contains("retrofit2/http/Query")) location = "query";
            else if (annStr.contains("retrofit2/http/Field")) location = "body";
            if (location == null) continue;

            String fieldName = extractAnnotationName(annStr);
            if (fieldName == null) continue;

            for (ProtocolFieldPattern pfp : PROTOCOL_FIELD_PATTERNS) {
                if (pfp.location().equals(location) && pfp.namePattern().matcher(fieldName).find()) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("field_name", fieldName);
                    field.put("field_role", pfp.fieldRole());
                    field.put("location", location);
                    field.put("class_name", className);
                    field.put("method_name", methodName);
                    field.put("confidence", 0.9);
                    field.put("source", "annotation");
                    fields.add(field);
                    break;
                }
            }
        }

        // 2. Check @Headers annotation for static header fields
        for (Object annObj : annotations) {
            String annStr = annObj.toString();
            if (!annStr.contains("retrofit2/http/Headers")) continue;
            List<String> headerValues = extractHeadersValues(annStr);
            for (String headerLine : headerValues) {
                String[] parts = headerLine.split(":", 2);
                if (parts.length < 2) continue;
                String headerName = parts[0].trim();
                for (ProtocolFieldPattern pfp : PROTOCOL_FIELD_PATTERNS) {
                    if (pfp.location().equals("header") && pfp.namePattern().matcher(headerName).find()) {
                        Map<String, Object> field = new LinkedHashMap<>();
                        field.put("field_name", headerName);
                        field.put("field_role", pfp.fieldRole());
                        field.put("location", "header");
                        field.put("class_name", className);
                        field.put("method_name", methodName);
                        field.put("confidence", 0.85);
                        field.put("source", "headers_annotation");
                        fields.add(field);
                        break;
                    }
                }
            }
        }

        // 3. Check parameter annotations
        if (parameterAnnotations != null) {
            for (Object paramAnnObj : parameterAnnotations) {
                String paramAnnStr = paramAnnObj.toString();
                String location = null;
                if (paramAnnStr.contains("retrofit2/http/Header")) location = "header";
                else if (paramAnnStr.contains("retrofit2/http/Query")) location = "query";
                else if (paramAnnStr.contains("retrofit2/http/Field")) location = "body";
                if (location == null) continue;

                String paramName = extractAnnotationName(paramAnnStr);
                if (paramName == null) continue;

                for (ProtocolFieldPattern pfp : PROTOCOL_FIELD_PATTERNS) {
                    if (pfp.location().equals(location) && pfp.namePattern().matcher(paramName).find()) {
                        Map<String, Object> field = new LinkedHashMap<>();
                        field.put("field_name", paramName);
                        field.put("field_role", pfp.fieldRole());
                        field.put("location", location);
                        field.put("class_name", className);
                        field.put("method_name", methodName);
                        field.put("confidence", 0.9);
                        field.put("source", "parameter_annotation");
                        fields.add(field);
                        break;
                    }
                }
            }
        }

        return fields;
    }

    /**
     * Source-code-based protocol field scanning for indexing pipeline.
     * Uses regex on decompiled source to detect @Header/@Query/@Field annotations.
     * Called during parallelStream indexing alongside apiEndpointIndex.scanClass().
     */
    public void scanProtocolFields(String className, String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return;

        // Pattern: @Header("X-Sign"), @Query("token"), @Field("encryptData"), etc.
        Pattern annPattern = Pattern.compile(
                "@(Header|Query|Field)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
        // Pattern: @Headers({"X-Sign: value", "X-Token: value"})
        Pattern headersPattern = Pattern.compile(
                "@Headers\\s*\\(\\s*\\{([^}]+)\\}\\s*\\)");
        // Pattern: method declaration after annotation
        Pattern methodPattern = Pattern.compile(
                "(?:public|private|protected)?\\s*(?:static)?\\s*(?:abstract)?\\s*[\\w<>\\[\\]?,\\s]+\\s+(\\w+)\\s*\\(");

        List<Map<String, Object>> allFields = new ArrayList<>();

        // Scan for @Header/@Query/@Field annotations
        Matcher matcher = annPattern.matcher(sourceCode);
        while (matcher.find()) {
            String annType = matcher.group(1);
            String fieldName = matcher.group(2);

            String location = switch (annType) {
                case "Header" -> "header";
                case "Query" -> "query";
                case "Field" -> "body";
                default -> null;
            };
            if (location == null) continue;

            // Find method name near this annotation
            String methodName = findMethodNear(sourceCode, matcher.end(), methodPattern);

            for (ProtocolFieldPattern pfp : PROTOCOL_FIELD_PATTERNS) {
                if (pfp.location().equals(location) && pfp.namePattern().matcher(fieldName).find()) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("field_name", fieldName);
                    field.put("field_role", pfp.fieldRole());
                    field.put("location", location);
                    field.put("class_name", className);
                    field.put("method_name", methodName);
                    field.put("confidence", 0.85); // source-code-based, slightly lower than AST
                    field.put("source", "index_scan");
                    allFields.add(field);
                    break;
                }
            }
        }

        // Scan for @Headers annotation
        Matcher headersMatcher = headersPattern.matcher(sourceCode);
        while (headersMatcher.find()) {
            String content = headersMatcher.group(1);
            String methodName = findMethodNear(sourceCode, headersMatcher.end(), methodPattern);

            for (String part : content.split(",")) {
                String trimmed = part.trim().replaceAll("^\"|\"$", "");
                String[] kv = trimmed.split(":", 2);
                if (kv.length < 2) continue;
                String headerName = kv[0].trim();

                for (ProtocolFieldPattern pfp : PROTOCOL_FIELD_PATTERNS) {
                    if (pfp.location().equals("header") && pfp.namePattern().matcher(headerName).find()) {
                        Map<String, Object> field = new LinkedHashMap<>();
                        field.put("field_name", headerName);
                        field.put("field_role", pfp.fieldRole());
                        field.put("location", "header");
                        field.put("class_name", className);
                        field.put("method_name", methodName);
                        field.put("confidence", 0.85);
                        field.put("source", "index_scan_headers");
                        allFields.add(field);
                        break;
                    }
                }
            }
        }

        // Store results in SecurityTag for each method
        for (Map<String, Object> field : allFields) {
            String methodKey = className + "#" + field.get("method_name");
            SecurityTag tag = annotations.computeIfAbsent(methodKey, k -> new SecurityTag());
            tag.addProtocolFields(List.of(field));
        }
    }

    private String findMethodNear(String source, int afterPos, Pattern methodPattern) {
        int searchEnd = Math.min(afterPos + 300, source.length());
        String snippet = source.substring(afterPos, searchEnd);
        Matcher m = methodPattern.matcher(snippet);
        if (m.find()) {
            return m.group(1);
        }
        return "unknown";
    }

    /**
     * Extract the name/value from an annotation string.
     * Handles formats like @Header("X-Sign") or @Query("token").
     */
    private String extractAnnotationName(String annStr) {
        int parenStart = annStr.indexOf("(\"");
        if (parenStart < 0) return null;
        int parenEnd = annStr.indexOf("\")", parenStart);
        if (parenEnd < 0) return null;
        return annStr.substring(parenStart + 2, parenEnd);
    }

    /**
     * Extract header values from @Headers annotation.
     * @Headers({"X-Sign: value", "X-Token: value"})
     */
    private List<String> extractHeadersValues(String annStr) {
        List<String> values = new ArrayList<>();
        int start = annStr.indexOf('{');
        int end = annStr.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return values;
        String content = annStr.substring(start + 1, end);
        for (String part : content.split(",")) {
            String trimmed = part.trim().replaceAll("^\"|\"$", "");
            if (trimmed.contains(":")) {
                values.add(trimmed);
            }
        }
        return values;
    }

    public Map<String, Object> getTagAsMap(String methodKey) {
        return getTagAsMap(methodKey, null);
    }

    /**
     * Get security tags as a Map for JSON serialization (used by CodeHandler).
     * When ruleEngine is provided, also includes rules_matched.
     */
    public Map<String, Object> getTagAsMap(String methodKey, RuleEngine ruleEngine) {
        SecurityTag tag = annotations.get(methodKey);
        if (tag == null) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("is_sink", tag.hasSinks());
        if (tag.hasSinks()) {
            result.put("sink_categories", new ArrayList<>(tag.getSinkCategories()));
        }
        result.put("is_source", tag.hasSources());
        if (tag.hasSources()) {
            result.put("source_categories", new ArrayList<>(tag.getSourceCategories()));
        }

        // protocol_fields from AST-level detection
        if (!tag.getProtocolFields().isEmpty()) {
            result.put("protocol_fields", tag.getProtocolFields());
        }

        // rules_matched from Layer 3 (if available)
        if (ruleEngine != null) {
            int hash = methodKey.indexOf('#');
            String className = hash > 0 ? methodKey.substring(0, hash) : methodKey;
            String methodName = hash > 0 ? methodKey.substring(hash + 1) : "";
            List<String> matchedRules = new ArrayList<>();
            for (var finding : ruleEngine.getClassFindings(className)) {
                if ("*".equals(finding.methodName) || methodName.equals(finding.methodName)) {
                    matchedRules.add(finding.ruleId);
                }
            }
            if (!matchedRules.isEmpty()) {
                result.put("rules_matched", matchedRules);
            }
        }

        return result;
    }

    public Map<String, Object> getClassSummaryAsMap(String className) {
        return getClassSummaryAsMap(className, null);
    }

    /**
     * Get class-level security summary. When ruleEngine is provided,
     * includes rules_triggered and highest_severity per spec.
     */
    public Map<String, Object> getClassSummaryAsMap(String className, RuleEngine ruleEngine) {
        ClassSecuritySummary summary = classSummaries.get(className);
        if (summary == null) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_sinks", summary.totalSinks);
        result.put("total_sources", summary.totalSources);
        result.put("sink_categories", new ArrayList<>(summary.sinkCategories));
        result.put("source_categories", new ArrayList<>(summary.sourceCategories));
        result.put("risk_level", summary.riskLevel);

        // Protocol fields from AST-level detection
        if (!summary.protocolFields.isEmpty()) {
            result.put("protocol_fields", summary.protocolFields);
        }

        // Layer 3 enrichment
        if (ruleEngine != null) {
            var classFindings = ruleEngine.getClassFindings(className);
            result.put("rules_triggered", classFindings.size());
            String highest = "low";
            for (var finding : classFindings) {
                if ("critical".equals(finding.severity)) { highest = "critical"; break; }
                if ("high".equals(finding.severity) && !"critical".equals(highest)) highest = "high";
                if ("medium".equals(finding.severity) && "low".equals(highest)) highest = "medium";
            }
            result.put("highest_severity", highest);
        }

        return result;
    }

    // ==================== Internal ====================

    private String matchAPI(String fullId, Map<String, String> registry) {
        // fullId format from JADX: "Ljavax/crypto/Cipher;.getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;"
        // We need to normalize and match
        String normalized = normalizeMethodId(fullId);
        // Try exact match
        String result = registry.get(normalized);
        if (result != null) return result;

        // Try prefix match (for overloaded methods)
        for (Map.Entry<String, String> entry : registry.entrySet()) {
            if (normalized.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Normalize JADX method ID format to dotted format.
     * "Ljavax/crypto/Cipher;.getInstance(Ljava/lang/String;)..." → "javax.crypto.Cipher.getInstance"
     */
    static String normalizeMethodId(String fullId) {
        if (fullId == null) return "";
        String s = fullId;

        // Remove leading 'L' and trailing signature
        if (s.startsWith("L")) s = s.substring(1);
        int parenIdx = s.indexOf('(');
        if (parenIdx > 0) s = s.substring(0, parenIdx);

        // Remove trailing ';' before '.'
        s = s.replace(";.", ".");
        s = s.replace(";", "");
        s = s.replace('/', '.');

        return s;
    }

    private String computeRiskLevel(ClassSecuritySummary summary) {
        if (summary.sinkCategories.contains("exec") || summary.sinkCategories.contains("sql")) return "critical";
        if (summary.sinkCategories.contains("webview") && summary.sourceCategories.contains("deeplink")) return "critical";
        if (summary.totalSinks >= 3) return "high";
        if (summary.totalSinks >= 1) return "medium";
        return "low";
    }

    private String[] parseMethodKey(String methodKey) {
        // Format: "com.pkg.Class#methodName(sig)V" or "com.pkg.Class#methodName"
        int hash = methodKey.indexOf('#');
        if (hash < 0) return new String[]{methodKey, "", ""};
        String cls = methodKey.substring(0, hash);
        String rest = methodKey.substring(hash + 1);
        int paren = rest.indexOf('(');
        if (paren > 0) {
            return new String[]{cls, rest.substring(0, paren), rest.substring(paren)};
        }
        return new String[]{cls, rest, ""};
    }

    public void clear() {
        annotations.clear();
        classSummaries.clear();
        totalSinks = 0;
        totalSources = 0;
    }

    // ==================== Inner types ====================

    public static class SecurityTag {
        private final Set<String> sinkCategories = ConcurrentHashMap.newKeySet();
        private final Set<String> sourceCategories = ConcurrentHashMap.newKeySet();
        private final List<String> sinkApis = Collections.synchronizedList(new ArrayList<>());
        private final List<String> sourceApis = Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, Object>> protocolFields = Collections.synchronizedList(new ArrayList<>());

        public void addSink(String category, String api) {
            sinkCategories.add(category);
            sinkApis.add(api);
        }

        public void addSource(String category, String api) {
            sourceCategories.add(category);
            sourceApis.add(api);
        }

        public void addProtocolFields(List<Map<String, Object>> fields) {
            if (fields != null) protocolFields.addAll(fields);
        }

        public boolean hasSinks() { return !sinkCategories.isEmpty(); }
        public boolean hasSources() { return !sourceCategories.isEmpty(); }
        public int getSinkCount() { return sinkApis.size(); }
        public int getSourceCount() { return sourceApis.size(); }
        public Set<String> getSinkCategories() { return sinkCategories; }
        public Set<String> getSourceCategories() { return sourceCategories; }
        public List<String> getSinkApis() { return sinkApis; }
        public List<String> getSourceApis() { return sourceApis; }
        public List<Map<String, Object>> getProtocolFields() { return protocolFields; }
    }

    public static class ClassSecuritySummary {
        public int totalSinks = 0;
        public int totalSources = 0;
        public Set<String> sinkCategories = new HashSet<>();
        public Set<String> sourceCategories = new HashSet<>();
        public String riskLevel = "low";
        public List<Map<String, Object>> protocolFields = new ArrayList<>();
    }
}
