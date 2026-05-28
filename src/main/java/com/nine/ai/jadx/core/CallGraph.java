package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 2: 双向调用图。
 * 在 CodeIndex 遍历时扫描每个方法的 InvokeNode，构建 caller → callee 与 callee → caller 映射。
 *
 * 支持：
 * - getCallers(method): 谁调用了这个方法
 * - getCallees(method): 这个方法调用了谁
 * - traceCallChain(from, direction, maxDepth): BFS 构建调用链
 */
public class CallGraph {
    private static final Logger LOG = LoggerFactory.getLogger(CallGraph.class);

    // caller → Set<callee>
    private final ConcurrentHashMap<String, Set<String>> forwardEdges = new ConcurrentHashMap<>();
    // callee → Set<caller>
    private final ConcurrentHashMap<String, Set<String>> reverseEdges = new ConcurrentHashMap<>();
    // Entry points: exported component's public/protected methods
    private final Set<String> entryPoints = ConcurrentHashMap.newKeySet();

    private final AtomicInteger edgeCount = new AtomicInteger(0);

    // ==================== Build API ====================

    /**
     * Record a call edge. Called during CodeIndex traversal for each InvokeNode.
     *
     * @param callerMethod The method that contains the invoke (format: "pkg.Class#method(sig)V")
     * @param calleeMethod The full ID of the called method (JADX format, will be normalized)
     */
    public void addEdge(String callerMethod, String calleeMethod) {
        // Normalize the callee from JADX format
        String normalizedCallee = normalizeCallTarget(calleeMethod);
        if (normalizedCallee.isEmpty()) return;

        forwardEdges.computeIfAbsent(callerMethod, k -> ConcurrentHashMap.newKeySet())
                .add(normalizedCallee);
        reverseEdges.computeIfAbsent(normalizedCallee, k -> ConcurrentHashMap.newKeySet())
                .add(callerMethod);
        edgeCount.incrementAndGet();
    }

    /**
     * Batch add edges from a single caller. More efficient than individual addEdge calls.
     */
    public void addEdges(String callerMethod, List<String> calleeMethodIds) {
        for (String calleeId : calleeMethodIds) {
            addEdge(callerMethod, calleeId);
        }
    }

    /**
     * Mark a method as an entry point (exported component's public/protected methods).
     */
    public void markEntryPoint(String methodKey) {
        entryPoints.add(methodKey);
    }

    /**
     * Check if a method is an entry point.
     */
    public boolean isEntryPoint(String methodKey) {
        return entryPoints.contains(methodKey);
    }

    // ==================== Query API ====================

    /**
     * Get all methods that call the given method (reverse lookup).
     */
    public Set<String> getCallers(String methodKey) {
        Set<String> callers = reverseEdges.get(methodKey);
        return callers != null ? Collections.unmodifiableSet(callers) : Collections.emptySet();
    }

    /**
     * Get all methods called by the given method (forward lookup).
     */
    public Set<String> getCallees(String methodKey) {
        Set<String> callees = forwardEdges.get(methodKey);
        return callees != null ? Collections.unmodifiableSet(callees) : Collections.emptySet();
    }

    /**
     * Trace a call chain using BFS.
     *
     * @param startMethod  Starting method key
     * @param direction    "callers" (who calls this?) or "callees" (what does this call?)
     * @param maxDepth     Maximum BFS depth (typically 5-10)
     * @return List of layers, each layer is the set of methods at that depth
     */
    public List<List<String>> traceCallChain(String startMethod, String direction, int maxDepth) {
        List<List<String>> layers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(startMethod);

        Queue<String> currentLevel = new LinkedList<>();
        currentLevel.add(startMethod);

        boolean isCallers = "callers".equalsIgnoreCase(direction);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            Queue<String> nextLevel = new LinkedList<>();
            List<String> layerResults = new ArrayList<>();

            for (String method : currentLevel) {
                Set<String> neighbors = isCallers ? getCallers(method) : getCallees(method);
                for (String neighbor : neighbors) {
                    if (visited.add(neighbor)) {
                        layerResults.add(neighbor);
                        nextLevel.add(neighbor);
                    }
                }
            }

            if (!layerResults.isEmpty()) {
                layers.add(layerResults);
            }
            currentLevel = nextLevel;
        }

        return layers;
    }

    /**
     * Trace a call chain as a nested tree structure (per spec 2.2).
     * Returns a tree node with children, plus paths_to_entry strings.
     */
    public CallChainResult traceCallChainTree(String startMethod, String direction, int maxDepth,
                                                SecurityAnnotator secAnnotator) {
        boolean isUp = "up".equalsIgnoreCase(direction) || "callers".equalsIgnoreCase(direction);

        Set<String> visited = new HashSet<>();
        visited.add(startMethod);

        // Build root node
        Map<String, Object> rootNode = buildTreeNode(startMethod, secAnnotator);
        List<String> pathsToEntry = new ArrayList<>();

        // Recursive BFS tree build
        buildTree(rootNode, startMethod, isUp, maxDepth, 0, visited, secAnnotator,
                new ArrayList<>(List.of(formatMethodShort(startMethod))), pathsToEntry);

        CallChainResult result = new CallChainResult();
        result.tree = rootNode;
        result.pathsToEntry = pathsToEntry;
        result.totalPaths = pathsToEntry.size();

        // Count total nodes
        result.totalNodes = countNodes(rootNode);

        return result;
    }

    private void buildTree(Map<String, Object> parentNode, String parentKey,
                           boolean isUp, int maxDepth, int currentDepth,
                           Set<String> visited, SecurityAnnotator secAnnotator,
                           List<String> currentPath, List<String> pathsToEntry) {
        if (currentDepth >= maxDepth) return;

        Set<String> neighbors = isUp ? getCallers(parentKey) : getCallees(parentKey);
        if (neighbors == null || neighbors.isEmpty()) return;

        // Skip framework classes
        List<Map<String, Object>> children = new ArrayList<>();
        int fanout = 0;
        for (String neighbor : neighbors) {
            if (fanout >= 50) break; // Fan-out cap per spec
            if (visited.contains(neighbor)) continue;
            if (isFrameworkClass(neighbor)) continue;

            visited.add(neighbor);
            fanout++;

            Map<String, Object> childNode = buildTreeNode(neighbor, secAnnotator);
            children.add(childNode);

            // Track path
            List<String> newPath = new ArrayList<>(currentPath);
            newPath.add(formatMethodShort(neighbor));

            // Check if this is an entry point
            if (isEntryPoint(neighbor)) {
                // Reverse the path for up direction to show entry→target
                if (isUp) {
                    List<String> reversed = new ArrayList<>(newPath);
                    Collections.reverse(reversed);
                    pathsToEntry.add(String.join(" → ", reversed));
                } else {
                    pathsToEntry.add(String.join(" → ", newPath));
                }
            }

            // Recurse
            buildTree(childNode, neighbor, isUp, maxDepth, currentDepth + 1,
                    visited, secAnnotator, newPath, pathsToEntry);
        }

        if (!children.isEmpty()) {
            String childKey = isUp ? "callers" : "callees";
            parentNode.put(childKey, children);
        }
    }

    private Map<String, Object> buildTreeNode(String methodKey, SecurityAnnotator secAnnotator) {
        Map<String, Object> node = new LinkedHashMap<>();
        int hash = methodKey.indexOf('#');
        if (hash > 0) {
            node.put("class", methodKey.substring(0, hash));
            node.put("method", methodKey.substring(hash + 1));
        } else {
            node.put("class", methodKey);
            node.put("method", "");
        }

        node.put("is_entry_point", isEntryPoint(methodKey));

        if (secAnnotator != null) {
            SecurityAnnotator.SecurityTag tag = secAnnotator.getTag(methodKey);
            if (tag != null) {
                if (tag.hasSinks()) {
                    node.put("is_sink", true);
                    node.put("sink_category", tag.getSinkCategories().iterator().next());
                }
                if (tag.hasSources()) {
                    node.put("is_source", true);
                    node.put("source_category", tag.getSourceCategories().iterator().next());
                }
            }
        }

        return node;
    }

    private String formatMethodShort(String methodKey) {
        int hash = methodKey.indexOf('#');
        if (hash < 0) return methodKey;
        String cls = methodKey.substring(0, hash);
        String method = methodKey.substring(hash + 1);
        // Extract simple class name
        int lastDot = cls.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? cls.substring(lastDot + 1) : cls;
        // Strip signature from method
        int paren = method.indexOf('(');
        String simpleMethod = paren > 0 ? method.substring(0, paren) : method;
        return simpleName + "." + simpleMethod;
    }

    @SuppressWarnings("unchecked")
    private int countNodes(Map<String, Object> node) {
        int count = 1;
        List<Map<String, Object>> callers = (List<Map<String, Object>>) node.get("callers");
        if (callers != null) {
            for (Map<String, Object> child : callers) {
                count += countNodes(child);
            }
        }
        List<Map<String, Object>> callees = (List<Map<String, Object>>) node.get("callees");
        if (callees != null) {
            for (Map<String, Object> child : callees) {
                count += countNodes(child);
            }
        }
        return count;
    }

    private boolean isFrameworkClass(String methodKey) {
        return methodKey.startsWith("android.") || methodKey.startsWith("java.") ||
                methodKey.startsWith("kotlin.") || methodKey.startsWith("androidx.") ||
                methodKey.startsWith("com.google.android.");
    }

    public static class CallChainResult {
        public Map<String, Object> tree;
        public List<String> pathsToEntry;
        public int totalPaths;
        public int totalNodes;
    }

    /**
     * Find the shortest path between two methods.
     * Uses bidirectional BFS for efficiency.
     *
     * @return The path as a list of method keys, or empty list if no path found
     */
    public List<String> findPath(String from, String to, int maxDepth) {
        if (from.equals(to)) return List.of(from);

        // Forward BFS from 'from'
        Map<String, String> forwardParent = new HashMap<>();
        forwardParent.put(from, null);
        Queue<String> forwardQueue = new LinkedList<>();
        forwardQueue.add(from);

        // Backward BFS from 'to'
        Map<String, String> backwardParent = new HashMap<>();
        backwardParent.put(to, null);
        Queue<String> backwardQueue = new LinkedList<>();
        backwardQueue.add(to);

        for (int depth = 0; depth < maxDepth; depth++) {
            // Expand forward one level
            int fSize = forwardQueue.size();
            for (int i = 0; i < fSize; i++) {
                String current = forwardQueue.poll();
                if (current == null) break;
                Set<String> callees = getCallees(current);
                for (String callee : callees) {
                    if (!forwardParent.containsKey(callee)) {
                        forwardParent.put(callee, current);
                        forwardQueue.add(callee);
                        // Check intersection
                        if (backwardParent.containsKey(callee)) {
                            return buildPath(forwardParent, backwardParent, callee);
                        }
                    }
                }
            }

            // Expand backward one level
            int bSize = backwardQueue.size();
            for (int i = 0; i < bSize; i++) {
                String current = backwardQueue.poll();
                if (current == null) break;
                Set<String> callers = getCallers(current);
                for (String caller : callers) {
                    if (!backwardParent.containsKey(caller)) {
                        backwardParent.put(caller, current);
                        backwardQueue.add(caller);
                        // Check intersection
                        if (forwardParent.containsKey(caller)) {
                            return buildPath(forwardParent, backwardParent, caller);
                        }
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    // ==================== Stats ====================

    public int getEdgeCount() {
        return edgeCount.get();
    }

    public int getNodeCount() {
        Set<String> nodes = new HashSet<>();
        nodes.addAll(forwardEdges.keySet());
        nodes.addAll(reverseEdges.keySet());
        return nodes.size();
    }

    /**
     * Get methods with highest in-degree (most called).
     */
    public List<Map.Entry<String, Integer>> getHotMethods(int topN) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : reverseEdges.entrySet()) {
            entries.add(Map.entry(e.getKey(), e.getValue().size()));
        }
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries.subList(0, Math.min(topN, entries.size()));
    }

    public void clear() {
        forwardEdges.clear();
        reverseEdges.clear();
        entryPoints.clear();
        edgeCount.set(0);
    }

    // ==================== Internal ====================

    /**
     * Normalize JADX call target ID to methodKey format.
     * Input: "Lcom/example/Foo;.bar(Ljava/lang/String;)V"
     * Output: "com.example.Foo#bar(Ljava/lang/String;)V"
     */
    static String normalizeCallTarget(String fullId) {
        if (fullId == null || fullId.isEmpty()) return "";
        String s = fullId;

        // Remove leading 'L'
        if (s.startsWith("L")) s = s.substring(1);

        // Find the method separator (";.")
        int methodSep = s.indexOf(";.");
        if (methodSep < 0) {
            // No method part, just return class
            s = s.replace(";", "").replace('/', '.');
            return s;
        }

        String classPart = s.substring(0, methodSep).replace('/', '.');
        String methodPart = s.substring(methodSep + 2); // skip ";."

        return classPart + "#" + methodPart;
    }

    private List<String> buildPath(Map<String, String> forwardParent, Map<String, String> backwardParent, String meeting) {
        // Build forward path (from → meeting)
        LinkedList<String> path = new LinkedList<>();
        String node = meeting;
        while (node != null) {
            path.addFirst(node);
            node = forwardParent.get(node);
        }

        // Build backward path (meeting → to)
        node = backwardParent.get(meeting);
        while (node != null) {
            path.addLast(node);
            node = backwardParent.get(node);
        }

        return path;
    }
}
