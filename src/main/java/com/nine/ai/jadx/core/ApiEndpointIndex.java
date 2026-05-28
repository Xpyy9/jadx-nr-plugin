package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2: API 端点索引。
 * 在 CodeIndex 遍历时扫描每个类的源码，提取 Retrofit/OkHttp 的 HTTP 端点注解。
 *
 * 提取的信息：
 * - HTTP method (GET/POST/PUT/DELETE/PATCH/HEAD)
 * - Path template (e.g., "/api/v1/users/{id}")
 * - Containing class and method
 * - Base URL (if detectable from companion Retrofit builder)
 */
public class ApiEndpointIndex {
    private static final Logger LOG = LoggerFactory.getLogger(ApiEndpointIndex.class);

    // All discovered endpoints
    private final List<ApiEndpoint> endpoints = Collections.synchronizedList(new ArrayList<>());

    // className → list of endpoints in that class
    private final ConcurrentHashMap<String, List<ApiEndpoint>> classEndpoints = new ConcurrentHashMap<>();

    // Detected base URLs
    private final Set<String> baseUrls = ConcurrentHashMap.newKeySet();

    private final AtomicInteger totalEndpoints = new AtomicInteger(0);

    // Interceptor chain detection (for auth_mechanisms in attackSurface)
    private final List<InterceptorInfo> interceptors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> tokenStorageRefs = Collections.synchronizedList(new ArrayList<>());

    // ==================== Patterns ====================

    // Retrofit annotation pattern: @GET("path"), @POST("path"), etc.
    private static final Pattern RETROFIT_ANNOTATION = Pattern.compile(
            "@(GET|POST|PUT|DELETE|PATCH|HEAD|HTTP)\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    // Retrofit @Url parameter (dynamic URL)
    private static final Pattern RETROFIT_URL_PARAM = Pattern.compile(
            "@Url\\s+(?:String|HttpUrl|Uri)\\s+(\\w+)");

    // Retrofit @Headers annotation
    private static final Pattern RETROFIT_HEADERS = Pattern.compile(
            "@Headers\\s*\\(\\s*\\{?([^)}]+)\\}?\\s*\\)");

    // Retrofit @Header parameter
    private static final Pattern RETROFIT_HEADER_PARAM = Pattern.compile(
            "@Header\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    // Retrofit base URL in builder pattern
    private static final Pattern BASE_URL_PATTERN = Pattern.compile(
            "\\.baseUrl\\s*\\(\\s*\"(https?://[^\"]+)\"\\s*\\)");

    // OkHttp Request.Builder URL
    private static final Pattern OKHTTP_URL_PATTERN = Pattern.compile(
            "new\\s+Request\\.Builder\\(\\)\\s*\\.url\\s*\\(\\s*\"(https?://[^\"]+)\"\\s*\\)");

    // HttpURLConnection URL
    private static final Pattern HTTP_URL_CONN_PATTERN = Pattern.compile(
            "new\\s+URL\\s*\\(\\s*\"(https?://[^\"]+)\"\\s*\\)");

    // Method signature pattern (to associate endpoint with method name)
    private static final Pattern METHOD_DECL_PATTERN = Pattern.compile(
            "(?:public|private|protected)?\\s*(?:static)?\\s*(?:abstract)?\\s*" +
                    "(?:[\\w<>\\[\\]?,\\s]+)\\s+(\\w+)\\s*\\(");

    // Interceptor detection patterns
    private static final Pattern INTERCEPTOR_IMPL = Pattern.compile(
            "implements\\s+(?:okhttp3\\.)?Interceptor\\b");
    private static final Pattern ADD_INTERCEPTOR = Pattern.compile(
            "\\.(addInterceptor|addNetworkInterceptor)\\s*\\(");
    private static final Pattern HEADER_ADD = Pattern.compile(
            "\\.(?:addHeader|header)\\s*\\(\\s*\"([^\"]+)\"");

    // Token storage detection patterns
    private static final Pattern SHARED_PREFS_TOKEN = Pattern.compile(
            "(?:getSharedPreferences|PreferenceManager\\.getDefaultSharedPreferences)" +
                    "[^;]*(?:getString|edit\\(\\))[^;]*\"([\\w_]+(?:token|key|secret|auth|session|jwt|bearer)[\\w_]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCRYPTED_PREFS = Pattern.compile(
            "EncryptedSharedPreferences[^;]*\"([\\w_]+)\"");

    // ==================== Build API ====================

    /**
     * Scan a class source code for API endpoints.
     * Called during CodeIndex traversal.
     *
     * @param className Full class name
     * @param sourceCode Decompiled source code
     */
    public void scanClass(String className, String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return;

        List<ApiEndpoint> found = new ArrayList<>();

        // Scan for Retrofit annotations
        scanRetrofitAnnotations(className, sourceCode, found);

        // Scan for base URLs
        scanBaseUrls(sourceCode);

        // Scan for direct OkHttp/HttpURLConnection URLs
        scanDirectUrls(className, sourceCode, found);

        if (!found.isEmpty()) {
            classEndpoints.put(className, found);
            endpoints.addAll(found);
            totalEndpoints.addAndGet(found.size());
        }

        // Scan for interceptors (auth_mechanisms)
        scanInterceptors(className, sourceCode);

        // Scan for token storage references
        scanTokenStorage(className, sourceCode);
    }

    private void scanInterceptors(String className, String sourceCode) {
        if (INTERCEPTOR_IMPL.matcher(sourceCode).find()) {
            InterceptorInfo info = new InterceptorInfo();
            info.className = className;

            // Determine type from context (heuristic: if class name contains "Network" -> network)
            info.type = "application_interceptor";

            // Extract headers this interceptor adds
            Matcher headerMatcher = HEADER_ADD.matcher(sourceCode);
            while (headerMatcher.find()) {
                info.addsHeaders.add(headerMatcher.group(1));
            }

            interceptors.add(info);
        }

        // Also detect addInterceptor/addNetworkInterceptor calls to map module → interceptor
        Matcher addMatcher = ADD_INTERCEPTOR.matcher(sourceCode);
        while (addMatcher.find()) {
            String methodType = addMatcher.group(1);
            // Update interceptor type if we find it as a network interceptor
            if ("addNetworkInterceptor".equals(methodType)) {
                // Try to find the class being added - look ahead for new ClassName(
                int searchEnd = Math.min(addMatcher.end() + 100, sourceCode.length());
                String snippet = sourceCode.substring(addMatcher.end(), searchEnd);
                Pattern newInstance = Pattern.compile("new\\s+([\\w.]+)\\s*\\(");
                Matcher newMatcher = newInstance.matcher(snippet);
                if (newMatcher.find()) {
                    String interceptorClass = newMatcher.group(1);
                    for (InterceptorInfo info : interceptors) {
                        String simpleName = info.className.contains(".")
                                ? info.className.substring(info.className.lastIndexOf('.') + 1)
                                : info.className;
                        if (interceptorClass.equals(simpleName) || interceptorClass.equals(info.className)) {
                            info.type = "network_interceptor";
                            info.calledFrom = className;
                        }
                    }
                }
            }
        }
    }

    private void scanTokenStorage(String className, String sourceCode) {
        Matcher prefsMatcher = SHARED_PREFS_TOKEN.matcher(sourceCode);
        while (prefsMatcher.find()) {
            tokenStorageRefs.add("SharedPreferences:" + prefsMatcher.group(1));
        }
        Matcher encMatcher = ENCRYPTED_PREFS.matcher(sourceCode);
        while (encMatcher.find()) {
            tokenStorageRefs.add("EncryptedSharedPreferences:" + encMatcher.group(1));
        }
    }

    private void scanRetrofitAnnotations(String className, String sourceCode, List<ApiEndpoint> found) {
        Matcher matcher = RETROFIT_ANNOTATION.matcher(sourceCode);
        while (matcher.find()) {
            String httpMethod = matcher.group(1);
            String path = matcher.group(2);

            // Find the method name this annotation belongs to
            String methodName = findMethodNameAfter(sourceCode, matcher.end());

            ApiEndpoint endpoint = new ApiEndpoint();
            endpoint.httpMethod = httpMethod;
            endpoint.path = path;
            endpoint.className = className;
            endpoint.methodName = methodName;
            endpoint.source = "retrofit";

            // Check for @Headers on this method
            endpoint.headers = findHeadersNear(sourceCode, matcher.start());

            found.add(endpoint);
        }
    }

    private void scanBaseUrls(String sourceCode) {
        Matcher matcher = BASE_URL_PATTERN.matcher(sourceCode);
        while (matcher.find()) {
            baseUrls.add(matcher.group(1));
        }
    }

    private void scanDirectUrls(String className, String sourceCode, List<ApiEndpoint> found) {
        // OkHttp direct URLs
        Matcher okMatcher = OKHTTP_URL_PATTERN.matcher(sourceCode);
        while (okMatcher.find()) {
            ApiEndpoint endpoint = new ApiEndpoint();
            endpoint.httpMethod = "GET"; // default, actual method set later in builder
            endpoint.path = okMatcher.group(1);
            endpoint.className = className;
            endpoint.methodName = findMethodContaining(sourceCode, okMatcher.start());
            endpoint.source = "okhttp";
            found.add(endpoint);
        }

        // HttpURLConnection direct URLs
        Matcher urlConnMatcher = HTTP_URL_CONN_PATTERN.matcher(sourceCode);
        while (urlConnMatcher.find()) {
            ApiEndpoint endpoint = new ApiEndpoint();
            endpoint.httpMethod = "GET";
            endpoint.path = urlConnMatcher.group(1);
            endpoint.className = className;
            endpoint.methodName = findMethodContaining(sourceCode, urlConnMatcher.start());
            endpoint.source = "httpurlconnection";
            found.add(endpoint);
        }
    }

    // ==================== Query API ====================

    public List<ApiEndpoint> getAllEndpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    public List<ApiEndpoint> getEndpointsByClass(String className) {
        List<ApiEndpoint> list = classEndpoints.get(className);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<ApiEndpoint> getEndpointsByMethod(String httpMethod) {
        List<ApiEndpoint> result = new ArrayList<>();
        for (ApiEndpoint ep : endpoints) {
            if (ep.httpMethod.equalsIgnoreCase(httpMethod)) {
                result.add(ep);
            }
        }
        return result;
    }

    public Set<String> getBaseUrls() {
        return Collections.unmodifiableSet(baseUrls);
    }

    public int getTotalEndpoints() {
        return totalEndpoints.get();
    }

    /**
     * Get endpoints as serializable maps for JSON response.
     */
    public List<Map<String, Object>> getEndpointsAsMap() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiEndpoint ep : endpoints) {
            result.add(ep.toMap());
        }
        return result;
    }

    /**
     * Get endpoint summary for attackSurface response.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_endpoints", totalEndpoints.get());
        summary.put("base_urls", new ArrayList<>(baseUrls));

        // Count by HTTP method
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        for (ApiEndpoint ep : endpoints) {
            methodCounts.merge(ep.httpMethod, 1, Integer::sum);
        }
        summary.put("by_method", methodCounts);

        // Count by source
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        for (ApiEndpoint ep : endpoints) {
            sourceCounts.merge(ep.source, 1, Integer::sum);
        }
        summary.put("by_source", sourceCounts);

        return summary;
    }

    public void clear() {
        endpoints.clear();
        classEndpoints.clear();
        baseUrls.clear();
        totalEndpoints.set(0);
        interceptors.clear();
        tokenStorageRefs.clear();
    }

    /**
     * Get auth mechanisms summary for attackSurface response.
     * Per spec section 7.1.6: interceptors, token_storage.
     */
    public Map<String, Object> getAuthMechanisms() {
        Map<String, Object> auth = new LinkedHashMap<>();

        // Interceptors
        List<Map<String, Object>> interceptorList = new ArrayList<>();
        for (InterceptorInfo info : interceptors) {
            interceptorList.add(info.toMap());
        }
        auth.put("interceptors", interceptorList);

        // Token storage (deduplicated)
        List<String> uniqueStorage = new ArrayList<>(new LinkedHashSet<>(tokenStorageRefs));
        auth.put("token_storage", uniqueStorage);

        return auth;
    }

    /**
     * Get enhanced endpoint summary for attackSurface response.
     * Includes auth_required, no_auth, top_endpoints per spec.
     */
    public Map<String, Object> getEnhancedSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", totalEndpoints.get());

        // Count by HTTP method
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        for (ApiEndpoint ep : endpoints) {
            methodCounts.merge(ep.httpMethod, 1, Integer::sum);
        }
        summary.put("by_method", methodCounts);

        summary.put("base_urls", new ArrayList<>(baseUrls));

        // Auth-related counts (heuristic: check if headers contain Authorization-like headers)
        int authRequired = 0;
        int noAuth = 0;
        for (ApiEndpoint ep : endpoints) {
            boolean hasAuth = ep.headers.stream().anyMatch(h ->
                    h.toLowerCase().contains("authorization") ||
                    h.toLowerCase().contains("token") ||
                    h.toLowerCase().contains("x-signature"));
            if (hasAuth) authRequired++;
            else noAuth++;
        }
        summary.put("auth_required", authRequired);
        summary.put("no_auth", noAuth);

        // Top endpoints (first 5)
        List<Map<String, Object>> topEndpoints = new ArrayList<>();
        int count = 0;
        for (ApiEndpoint ep : endpoints) {
            if (count >= 5) break;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", ep.httpMethod);
            item.put("path", ep.path);
            item.put("class", ep.className.contains(".")
                    ? ep.className.substring(ep.className.lastIndexOf('.') + 1) : ep.className);
            item.put("has_auth", ep.headers.stream().anyMatch(h ->
                    h.toLowerCase().contains("authorization") ||
                    h.toLowerCase().contains("token")));
            topEndpoints.add(item);
            count++;
        }
        summary.put("top_endpoints", topEndpoints);

        return summary;
    }

    // ==================== Inner types ====================

    public static class InterceptorInfo {
        public String className;
        public String type;              // "application_interceptor" or "network_interceptor"
        public List<String> addsHeaders = new ArrayList<>();
        public String calledFrom;        // module class where addInterceptor was called

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", className);
            map.put("type", type);
            if (!addsHeaders.isEmpty()) {
                map.put("adds_headers", addsHeaders);
            }
            if (calledFrom != null) {
                map.put("module", calledFrom);
            }
            return map;
        }
    }

    // ==================== Internal helpers ====================

    private String findMethodNameAfter(String source, int afterPos) {
        // Look for the next method declaration after the annotation
        Matcher m = METHOD_DECL_PATTERN.matcher(source);
        int searchStart = afterPos;
        // Limit search to next 200 chars
        int searchEnd = Math.min(afterPos + 200, source.length());
        String snippet = source.substring(searchStart, searchEnd);
        Matcher snippetMatcher = METHOD_DECL_PATTERN.matcher(snippet);
        if (snippetMatcher.find()) {
            return snippetMatcher.group(1);
        }
        return "unknown";
    }

    private String findMethodContaining(String source, int position) {
        // Search backwards from position for the most recent method declaration
        int searchStart = Math.max(0, position - 500);
        String snippet = source.substring(searchStart, position);
        Matcher m = METHOD_DECL_PATTERN.matcher(snippet);
        String lastMethod = "unknown";
        while (m.find()) {
            lastMethod = m.group(1);
        }
        return lastMethod;
    }

    private List<String> findHeadersNear(String source, int annotationStart) {
        // Look for @Headers annotation within 200 chars before
        int searchStart = Math.max(0, annotationStart - 200);
        String snippet = source.substring(searchStart, annotationStart);
        Matcher m = RETROFIT_HEADERS.matcher(snippet);
        if (m.find()) {
            String raw = m.group(1);
            List<String> headers = new ArrayList<>();
            for (String h : raw.split(",")) {
                String trimmed = h.trim().replace("\"", "");
                if (!trimmed.isEmpty()) headers.add(trimmed);
            }
            return headers;
        }
        return Collections.emptyList();
    }

    // ==================== Inner type ====================

    public static class ApiEndpoint {
        public String httpMethod;      // GET, POST, PUT, DELETE, PATCH, HEAD
        public String path;            // "/api/v1/users/{id}" or full URL
        public String className;       // Interface or class containing the endpoint
        public String methodName;      // Java method name
        public String source;          // "retrofit", "okhttp", "httpurlconnection"
        public List<String> headers = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("http_method", httpMethod);
            map.put("path", path);
            map.put("class", className);
            map.put("method", methodName);
            map.put("source", source);
            if (!headers.isEmpty()) {
                map.put("headers", headers);
            }
            return map;
        }
    }
}
