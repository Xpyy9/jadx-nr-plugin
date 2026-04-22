package com.nine.ai.jadx.server.handler.resource;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 资源文件内关键词搜索：在指定资源文件中按关键词检索匹配行 + 上下文，
 * 替代多次 getResourceFile 分页读取大文件。
 */
public class ResourceSearchHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(ResourceSearchHandler.class);
    private final HttpUtil http = HttpUtil.getInstance();

    private static final int DEFAULT_CONTEXT_LINES = 3;
    private static final int MAX_CONTEXT_LINES = 10;
    private static final int MAX_MATCHES = 50;
    private static final int MAX_CONTENT_LENGTH = 200000;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            http.sendError(exchange, 405, "Only GET allowed");
            return;
        }

        if (!PluginServer.getInstance().isRunning()) {
            http.sendError(exchange, 503, "Service unavailable");
            return;
        }

        Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
        String fileName = params.get("file_name");
        String keyword = params.get("keyword");
        int contextLines = HttpUtil.parseInt(params.get("context_lines"), DEFAULT_CONTEXT_LINES);
        contextLines = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));

        if (fileName == null || fileName.isBlank()) {
            http.sendError(exchange, 400, "Missing required parameter: file_name");
            return;
        }
        if (keyword == null || keyword.isBlank()) {
            http.sendError(exchange, 400, "Missing required parameter: keyword");
            return;
        }

        try {
            JadxDecompiler decompiler = JadxUtil.getDecompiler();
            if (decompiler == null) {
                http.sendError(exchange, 500, "Decompiler not available");
                return;
            }

            String content = getResourceContent(decompiler, fileName);
            if (content == null || content.isBlank()) {
                http.sendError(exchange, 404, "Resource not found: " + fileName);
                return;
            }

            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            Map<String, Object> result = searchInContent(content, fileName, keyword, contextLines);
            http.sendResponse(exchange, 200, http.toJson(result));

        } catch (Exception e) {
            logger.error("ResourceSearch failed", e);
            http.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private String getResourceContent(JadxDecompiler decompiler, String fileName) {
        Map<String, ResourceFile> cache = JadxUtil.getResourceCache(decompiler);
        if (cache == null) return null;

        ResourceFile target = cache.get(fileName);
        if (target != null) {
            return JadxUtil.getResourceContent(target);
        }
        // arsc fallback
        ResourceFile arsc = cache.get("resources.arsc");
        if (arsc != null) {
            return JadxUtil.getArscResourceContent(arsc, fileName);
        }
        return null;
    }

    private Map<String, Object> searchInContent(String content, String fileName, String keyword, int contextLines) {
        String[] lines = content.split("\\R");
        int totalLines = lines.length;
        String lowerKeyword = keyword.toLowerCase();

        // Find all matching line indices
        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < totalLines; i++) {
            if (lines[i].toLowerCase().contains(lowerKeyword)) {
                matchIndices.add(i);
            }
        }

        boolean truncated = matchIndices.size() > MAX_MATCHES;
        if (truncated) {
            matchIndices = matchIndices.subList(0, MAX_MATCHES);
        }

        // Merge overlapping context ranges to avoid duplicate lines
        List<int[]> ranges = mergeRanges(matchIndices, contextLines, totalLines);

        // Build match entries with context
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int[] range : ranges) {
            // Find which match indices fall within this range
            for (int matchIdx : matchIndices) {
                if (matchIdx >= range[0] && matchIdx <= range[1]) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("line_number", matchIdx + 1); // 1-based

                    // Content of the matching line
                    entry.put("content", lines[matchIdx]);

                    // Context before
                    List<String> before = new ArrayList<>();
                    for (int j = Math.max(range[0], matchIdx - contextLines); j < matchIdx; j++) {
                        before.add(lines[j]);
                    }
                    if (!before.isEmpty()) entry.put("context_before", before);

                    // Context after
                    List<String> after = new ArrayList<>();
                    for (int j = matchIdx + 1; j <= Math.min(range[1], matchIdx + contextLines); j++) {
                        after.add(lines[j]);
                    }
                    if (!after.isEmpty()) entry.put("context_after", after);

                    matches.add(entry);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "resource-search");
        result.put("file_name", fileName);
        result.put("keyword", keyword);
        result.put("total_lines", totalLines);
        result.put("match_count", truncated ? "> " + MAX_MATCHES : matches.size());
        result.put("matches", matches);
        result.put("truncated", truncated);

        return result;
    }

    /**
     * Merge overlapping context ranges to avoid duplicate output.
     * Each match at index i generates range [i-context, i+context].
     * Overlapping ranges are merged into a single continuous range.
     */
    private List<int[]> mergeRanges(List<Integer> matchIndices, int contextLines, int totalLines) {
        if (matchIndices.isEmpty()) return Collections.emptyList();

        List<int[]> ranges = new ArrayList<>();
        int start = Math.max(0, matchIndices.get(0) - contextLines);
        int end = Math.min(totalLines - 1, matchIndices.get(0) + contextLines);

        for (int i = 1; i < matchIndices.size(); i++) {
            int newStart = Math.max(0, matchIndices.get(i) - contextLines);
            int newEnd = Math.min(totalLines - 1, matchIndices.get(i) + contextLines);

            if (newStart <= end + 1) {
                // Merge: extend current range
                end = Math.max(end, newEnd);
            } else {
                // No overlap: save current range, start new one
                ranges.add(new int[]{start, end});
                start = newStart;
                end = newEnd;
            }
        }
        ranges.add(new int[]{start, end});
        return ranges;
    }
}
