package com.nine.ai.jadx.server.handler;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * /search 路由处理器
 * Actions: find, scan, findSinkSource
 */
public class SearchHandler extends BaseHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SearchHandler.class);

    private final CodeIndexManager codeIndex;

    public SearchHandler(AnalysisLayers layers, CodeIndexManager codeIndex) {
        super(layers);
        this.codeIndex = codeIndex;
    }

    @Override
    protected int requiredLayer(String action) {
        switch (action) {
            case "find": return 1;       // Needs CodeIndex
            case "scan": return 3;       // Needs RuleEngine
            case "findSinkSource": return 2; // Needs SecurityAnnotator
            default: return 1;
        }
    }

    @Override
    protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
        switch (action) {
            case "find":
                handleFind(exchange, params);
                break;
            case "scan":
                handleScan(exchange, params);
                break;
            case "findSinkSource":
                handleFindSinkSource(exchange, params);
                break;
            default:
                http.sendError(exchange, 400, "Unknown action for /search: '" + action + "'. Valid: find, scan, findSinkSource");
        }
    }

    private void handleFind(HttpExchange exchange, Map<String, String> params) throws IOException {
        String query = params.get("query");
        if (query == null || query.isBlank()) {
            http.sendError(exchange, 400, "find requires parameter 'query'");
            return;
        }

        String scope = params.getOrDefault("scope", "auto");
        int offset = HttpUtil.parseInt(params.get("offset"), 0);
        int limit = HttpUtil.parseInt(params.get("limit"), 50);

        Map<String, String> index = codeIndex.getCodeIndex();
        if (index == null || index.isEmpty()) {
            http.sendError(exchange, 503, "Code index not available");
            return;
        }

        List<Map<String, Object>> results;
        String strategy;

        switch (scope) {
            case "class":
                results = searchByClassName(index, query);
                strategy = "class_name";
                break;
            case "method":
                results = searchByMethodName(index, query);
                strategy = "method_name";
                break;
            case "code":
                results = searchInCode(index, query);
                strategy = "code_content";
                break;
            case "string":
                results = searchStringConstants(query);
                strategy = "string_constant";
                break;
            case "url":
                results = searchUrls(query);
                strategy = "url";
                break;
            case "secret":
                results = searchPossibleSecrets(query);
                strategy = "possible_secret";
                break;
            case "endpoint":
                results = searchEndpoints(query);
                strategy = "api_endpoint";
                break;
            case "auto":
            default:
                // Auto strategy: class → method → code
                results = searchByClassName(index, query);
                strategy = "class_name";
                if (results.isEmpty()) {
                    results = searchByMethodName(index, query);
                    strategy = "method_name";
                }
                if (results.isEmpty()) {
                    results = searchInCode(index, query);
                    strategy = "code_content";
                }
                break;
        }

        // Apply pagination
        int total = results.size();
        int fromIdx = Math.min(offset, total);
        int toIdx = Math.min(offset + limit, total);
        List<Map<String, Object>> paged = results.subList(fromIdx, toIdx);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "search-results");
        response.put("query", query);
        response.put("scope", scope);
        response.put("strategy", strategy);
        response.put("results", paged);
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("has_more", toIdx < total);

        http.sendResponse(exchange, 200, http.toJson(response));
    }

    private void handleScan(HttpExchange exchange, Map<String, String> params) throws IOException {
        String category = params.getOrDefault("category", "all");
        String severity = params.getOrDefault("severity", "info");
        int limit = HttpUtil.parseInt(params.get("limit"), 100);

        var ruleEngine = codeIndex.getRuleEngine();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "security-scan");
        response.put("category", category);
        response.put("min_severity", severity);
        response.put("summary", ruleEngine.getSummary());
        response.put("findings", ruleEngine.getFindingsAsMap(
                "all".equals(category) ? null : category, severity, limit));
        response.put("total_findings", ruleEngine.getFindings(
                "all".equals(category) ? null : category, severity).size());
        http.sendResponse(exchange, 200, http.toJson(response));
    }

    private void handleFindSinkSource(HttpExchange exchange, Map<String, String> params) throws IOException {
        String type = params.getOrDefault("type", "both");
        String category = params.get("category");

        var secAnnotator = codeIndex.getSecurityAnnotator();
        var callGraph = codeIndex.getCallGraph();

        List<Map<String, Object>> results = new ArrayList<>();

        if ("sink".equals(type) || "both".equals(type)) {
            results.addAll(secAnnotator.findSinks(category, callGraph));
        }
        if ("source".equals(type) || "both".equals(type)) {
            results.addAll(secAnnotator.findSources(category, callGraph));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "sink-source-list");
        response.put("filter_type", type);
        response.put("filter_category", category);
        response.put("results", results);
        response.put("total", results.size());
        response.put("sinks_total", secAnnotator.getTotalSinks());
        response.put("sources_total", secAnnotator.getTotalSources());
        http.sendResponse(exchange, 200, http.toJson(response));
    }

    // ==================== Search implementations ====================

    private List<Map<String, Object>> searchByClassName(Map<String, String> index, String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (String className : index.keySet()) {
            if (className.toLowerCase().contains(lowerQuery)) {
                // Skip third-party by default
                if (codeIndex.isThirdParty(className)) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class_name", className);
                entry.put("match_type", "class_name");
                entry.put("is_third_party", false);

                // Security summary from Layer 2 (if ready)
                if (layers.isReady(2)) {
                    Map<String, Object> secSummary = codeIndex.getSecurityAnnotator().getClassSummaryAsMap(className);
                    if (secSummary != null) {
                        entry.put("security_summary", secSummary);
                    }
                }

                results.add(entry);

                if (results.size() >= 200) break; // Hard cap
            }
        }
        return results;
    }

    private List<Map<String, Object>> searchByMethodName(Map<String, String> index, String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (codeIndex.isThirdParty(entry.getKey())) continue;

            String code = entry.getValue();
            // Quick heuristic: search for method declarations containing the query
            if (code.toLowerCase().contains(lowerQuery)) {
                // Check if it looks like a method name match
                String pattern = "\\b(public|private|protected|static)?\\s+\\w+\\s+" + Pattern.quote(query) + "\\s*\\(";
                if (code.matches("(?s).*" + pattern + ".*")) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("class_name", entry.getKey());
                    result.put("match_type", "method_name");
                    result.put("is_third_party", false);
                    results.add(result);

                    if (results.size() >= 100) break;
                }
            }
        }
        return results;
    }

    private List<Map<String, Object>> searchInCode(Map<String, String> index, String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (codeIndex.isThirdParty(entry.getKey())) continue;

            if (entry.getValue().toLowerCase().contains(lowerQuery)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("class_name", entry.getKey());
                result.put("match_type", "code_content");
                result.put("is_third_party", false);
                results.add(result);

                if (results.size() >= 100) break;
            }
        }
        return results;
    }

    private List<Map<String, Object>> searchStringConstants(String query) {
        var stringIndex = codeIndex.getStringConstantIndex();
        if (stringIndex.getTotalStrings() == 0) {
            // Fallback to code search if index not populated
            return searchInCode(codeIndex.getCodeIndex(), query);
        }
        var refs = stringIndex.search(query, com.nine.ai.jadx.core.StringConstantIndex.MatchMode.CONTAINS);
        return stringIndex.toMaps(refs);
    }

    private List<Map<String, Object>> searchUrls(String query) {
        var stringIndex = codeIndex.getStringConstantIndex();
        if (stringIndex.getTotalStrings() > 0) {
            var refs = stringIndex.findUrls();
            // Filter by query if provided
            if (query != null && !query.isBlank()) {
                String lq = query.toLowerCase();
                refs.removeIf(r -> !r.value.toLowerCase().contains(lq));
            }
            return stringIndex.toMaps(refs);
        }

        // Fallback: regex search in code
        Map<String, String> index = codeIndex.getCodeIndex();
        List<Map<String, Object>> results = new ArrayList<>();
        Pattern urlPattern = Pattern.compile("\"https?://[^\"]+\"");

        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (codeIndex.isThirdParty(entry.getKey())) continue;

            var matcher = urlPattern.matcher(entry.getValue());
            List<String> urls = new ArrayList<>();
            while (matcher.find() && urls.size() < 10) {
                String url = matcher.group();
                if (query == null || query.isBlank() || url.toLowerCase().contains(query.toLowerCase())) {
                    urls.add(url.substring(1, url.length() - 1)); // strip quotes
                }
            }
            if (!urls.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("class_name", entry.getKey());
                result.put("match_type", "url");
                result.put("urls", urls);
                result.put("is_third_party", false);
                results.add(result);
            }
            if (results.size() >= 50) break;
        }
        return results;
    }

    private List<Map<String, Object>> searchPossibleSecrets(String query) {
        var stringIndex = codeIndex.getStringConstantIndex();
        if (stringIndex.getTotalStrings() > 0) {
            var refs = stringIndex.findPossibleSecrets();
            return stringIndex.toMaps(refs);
        }

        // Fallback: regex search in code
        Map<String, String> index = codeIndex.getCodeIndex();
        List<Map<String, Object>> results = new ArrayList<>();
        Pattern secretPattern = Pattern.compile("\"[A-Za-z0-9+/=_\\-]{16,}\"");

        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (codeIndex.isThirdParty(entry.getKey())) continue;

            var matcher = secretPattern.matcher(entry.getValue());
            List<String> secrets = new ArrayList<>();
            while (matcher.find() && secrets.size() < 5) {
                secrets.add(matcher.group().substring(1, matcher.group().length() - 1));
            }
            if (!secrets.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("class_name", entry.getKey());
                result.put("match_type", "possible_secret");
                result.put("values", secrets);
                result.put("is_third_party", false);
                results.add(result);
            }
            if (results.size() >= 30) break;
        }
        return results;
    }

    private List<Map<String, Object>> searchEndpoints(String query) {
        // Use ApiEndpointIndex if Layer 2 is ready
        if (layers.isReady(2)) {
            var apiIndex = codeIndex.getApiEndpointIndex();
            List<Map<String, Object>> results = new ArrayList<>();
            for (var ep : apiIndex.getAllEndpoints()) {
                if (query == null || query.isBlank() ||
                        ep.path.toLowerCase().contains(query.toLowerCase()) ||
                        ep.className.toLowerCase().contains(query.toLowerCase())) {
                    results.add(ep.toMap());
                }
                if (results.size() >= 50) break;
            }
            return results;
        }

        // Fallback: regex search in code
        Map<String, String> index = codeIndex.getCodeIndex();
        List<Map<String, Object>> results = new ArrayList<>();
        Pattern retrofitPattern = Pattern.compile("@(GET|POST|PUT|DELETE|PATCH|HEAD)\\(\"([^\"]+)\"\\)");

        for (Map.Entry<String, String> entry : index.entrySet()) {
            if (codeIndex.isThirdParty(entry.getKey())) continue;

            var matcher = retrofitPattern.matcher(entry.getValue());
            List<Map<String, String>> endpoints = new ArrayList<>();
            while (matcher.find()) {
                String method = matcher.group(1);
                String path = matcher.group(2);
                if (query == null || query.isBlank() || path.toLowerCase().contains(query.toLowerCase())) {
                    endpoints.add(Map.of("method", method, "path", path));
                }
            }
            if (!endpoints.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("class_name", entry.getKey());
                result.put("match_type", "api_endpoint");
                result.put("endpoints", endpoints);
                result.put("is_third_party", false);
                results.add(result);
            }
            if (results.size() >= 30) break;
        }
        return results;
    }
}
