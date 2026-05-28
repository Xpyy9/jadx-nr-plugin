package com.nine.ai.jadx.core;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 4: SSA 数据流污点分析器（按需执行，不预热）。
 *
 * 分析策略:
 * - shallow: 方法内 SSA def-use 链追踪 (<10ms per method)
 * - deep: 方法内 + 跨方法参数传播 (<500ms)
 *
 * 算法:
 * 1. 遍历方法的 InsnNode 列表
 * 2. 识别 source 调用 (InvokeNode target ∈ SOURCE_APIS)
 * 3. source 返回值的 SSAVar → 标记为 tainted
 * 4. 沿 SSAVar.getUseList() 传播 taint
 * 5. 检查 tainted SSAVar 是否流入 sink 调用的参数
 * 6. 命中 → 记录 TaintFlow(source, path, sink)
 */
public class SSATaintAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(SSATaintAnalyzer.class);

    // Cache: methodKey → TaintResult
    private final ConcurrentHashMap<String, TaintResult> cache = new ConcurrentHashMap<>();
    private final AtomicInteger analyzedCount = new AtomicInteger(0);

    // Reference to other engines for cross-method analysis
    private final CallGraph callGraph;
    private final SecurityAnnotator securityAnnotator;

    public SSATaintAnalyzer(CallGraph callGraph, SecurityAnnotator securityAnnotator) {
        this.callGraph = callGraph;
        this.securityAnnotator = securityAnnotator;
    }

    // ==================== Analysis API ====================

    /**
     * Analyze a single method for taint flows.
     *
     * @param cls        JavaClass containing the method
     * @param method     JavaMethod to analyze
     * @param depthMode  "shallow" (intra-method) or "deep" (inter-method)
     * @return TaintResult with discovered flows
     */
    public TaintResult analyzeMethod(JavaClass cls, JavaMethod method, String depthMode) {
        String methodKey = cls.getFullName() + "#" + method.getName();

        // Check cache
        TaintResult cached = cache.get(methodKey);
        if (cached != null && (!"deep".equals(depthMode) || cached.isDeep)) {
            return cached;
        }

        TaintResult result = new TaintResult();
        result.methodKey = methodKey;
        result.className = cls.getFullName();
        result.methodName = method.getName();
        result.isDeep = "deep".equals(depthMode);

        try {
            MethodNode mth = method.getMethodNode();
            if (mth == null || mth.getInstructions() == null) {
                return result;
            }

            // Phase 1: Intra-method SSA taint analysis
            analyzeIntraMethod(mth, result);

            // Phase 2: Cross-method analysis (if deep)
            if ("deep".equals(depthMode) && callGraph != null) {
                analyzeCrossMethod(methodKey, result);
            }

        } catch (Exception e) {
            LOG.debug("Taint analysis failed for {}: {}", methodKey, e.getMessage());
            result.error = e.getMessage();
        }

        // Cache result
        cache.put(methodKey, result);
        analyzedCount.incrementAndGet();

        return result;
    }

    // ==================== Intra-method analysis ====================

    private void analyzeIntraMethod(MethodNode mth, TaintResult result) {
        InsnNode[] instructions = mth.getInstructions();
        if (instructions == null) return;

        // Step 1: Find all source calls and mark their outputs as tainted
        Set<SSAVar> taintedVars = new HashSet<>();
        Map<SSAVar, TaintSource> taintOrigins = new HashMap<>();

        for (InsnNode insn : instructions) {
            if (insn == null) continue;
            if (insn.getType() == InsnType.INVOKE) {
                InvokeNode invoke = (InvokeNode) insn;
                String target = invoke.getCallMth().toString();
                String normalized = SecurityAnnotator.normalizeMethodId(target);

                // Check if this is a source API
                if (isSourceAPI(normalized)) {
                    // The result register of this invoke is tainted
                    RegisterArg resultArg = insn.getResult();
                    if (resultArg != null && resultArg.getSVar() != null) {
                        SSAVar ssaVar = resultArg.getSVar();
                        taintedVars.add(ssaVar);
                        taintOrigins.put(ssaVar, new TaintSource(normalized, getInsnLine(insn)));
                    }
                }
            }
        }

        if (taintedVars.isEmpty()) {
            // Check if method parameters should be tainted
            checkTaintedParams(mth, taintedVars, taintOrigins, result);
        }

        if (taintedVars.isEmpty()) return;

        // Step 2: Propagate taint through SSA def-use chains
        Set<SSAVar> worklist = new HashSet<>(taintedVars);
        Set<SSAVar> visited = new HashSet<>();
        List<TaintStep> taintPath = new ArrayList<>();

        while (!worklist.isEmpty()) {
            Set<SSAVar> newTainted = new HashSet<>();
            for (SSAVar var : worklist) {
                if (!visited.add(var)) continue;

                TaintSource origin = taintOrigins.get(var);
                if (origin != null) {
                    taintPath.add(new TaintStep(
                            "v" + var.getRegNum(),
                            origin.api,
                            origin.line
                    ));
                }

                // Follow uses of this variable
                List<RegisterArg> uses = var.getUseList();
                if (uses == null) continue;

                for (RegisterArg use : uses) {
                    InsnNode useInsn = use.getParentInsn();
                    if (useInsn == null) continue;

                    // Check if this use flows into a sink
                    if (useInsn.getType() == InsnType.INVOKE) {
                        InvokeNode sinkInvoke = (InvokeNode) useInsn;
                        String sinkTarget = SecurityAnnotator.normalizeMethodId(
                                sinkInvoke.getCallMth().toString());
                        if (isSinkAPI(sinkTarget)) {
                            // Found a taint flow: source → sink
                            TaintFlow flow = new TaintFlow();
                            flow.source = origin != null ? origin : new TaintSource("param", -1);
                            flow.sink = new TaintSink(sinkTarget, getInsnLine(useInsn), getSinkCategory(sinkTarget));
                            flow.path = new ArrayList<>(taintPath);
                            flow.confidence = "high";
                            flow.taintType = "ssa_traced";
                            result.flows.add(flow);
                        }
                    }

                    // Propagate: if the use instruction produces a result, taint it
                    RegisterArg resultArg = useInsn.getResult();
                    if (resultArg != null && resultArg.getSVar() != null) {
                        SSAVar newVar = resultArg.getSVar();
                        if (!visited.contains(newVar)) {
                            newTainted.add(newVar);
                            taintedVars.add(newVar);
                            // Carry origin forward
                            if (origin != null && !taintOrigins.containsKey(newVar)) {
                                taintOrigins.put(newVar, origin);
                            }
                        }
                    }
                }
            }
            worklist = newTainted;
        }

        // Summary
        result.taintedParams = findTaintedParamIndices(mth, taintedVars);
        result.taintedReturn = isReturnTainted(mth, taintedVars);
        result.internalFlows = result.flows.size();
    }

    // ==================== Cross-method analysis ====================

    private void analyzeCrossMethod(String methodKey, TaintResult result) {
        if (result.flows.isEmpty() && result.taintedParams.isEmpty() && !result.taintedReturn) {
            return; // Nothing to propagate
        }

        // If method has tainted parameters, trace callers to see who passes tainted data
        if (!result.taintedParams.isEmpty()) {
            Set<String> callers = callGraph.getCallers(methodKey);
            for (String caller : callers) {
                // Check if caller has source tags
                SecurityAnnotator.SecurityTag callerTag = securityAnnotator.getTag(caller);
                if (callerTag != null && callerTag.hasSources()) {
                    TaintFlow crossFlow = new TaintFlow();
                    crossFlow.source = new TaintSource("cross_method:" + caller, -1);
                    crossFlow.sink = new TaintSink("param_of:" + methodKey, -1, "cross_method");
                    crossFlow.path = List.of(new TaintStep("caller", caller, -1));
                    crossFlow.confidence = "medium";
                    crossFlow.taintType = "cross_method_traced";
                    result.flows.add(crossFlow);
                    result.crossMethodFlows++;
                }
            }
        }

        // If method has tainted return, trace callees to see who uses the return value
        if (result.taintedReturn) {
            Set<String> callees = callGraph.getCallees(methodKey);
            // The callers of this method receive tainted data
            Set<String> callers = callGraph.getCallers(methodKey);
            for (String caller : callers) {
                SecurityAnnotator.SecurityTag callerTag = securityAnnotator.getTag(caller);
                if (callerTag != null && callerTag.hasSinks()) {
                    TaintFlow crossFlow = new TaintFlow();
                    crossFlow.source = new TaintSource("return_of:" + methodKey, -1);
                    crossFlow.sink = new TaintSink("sink_in:" + caller, -1, "cross_method");
                    crossFlow.path = List.of(new TaintStep("return", methodKey, -1));
                    crossFlow.confidence = "medium";
                    crossFlow.taintType = "cross_method_traced";
                    result.flows.add(crossFlow);
                    result.crossMethodFlows++;
                }
            }
        }
    }

    // ==================== Helper methods ====================

    private void checkTaintedParams(MethodNode mth, Set<SSAVar> taintedVars,
                                     Map<SSAVar, TaintSource> taintOrigins, TaintResult result) {
        // Method parameters coming from external sources are considered tainted
        // This is relevant for exported component callbacks (onCreate, onReceive, etc.)
        try {
            var args = mth.getArgRegs();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    RegisterArg arg = args.get(i);
                    if (arg != null && arg.getSVar() != null) {
                        String typeName = arg.getType() != null ? arg.getType().toString() : "";
                        // Intent, Uri, Bundle parameters are external data sources
                        if (typeName.contains("Intent") || typeName.contains("Uri") ||
                                typeName.contains("Bundle") || typeName.contains("String")) {
                            taintedVars.add(arg.getSVar());
                            taintOrigins.put(arg.getSVar(), new TaintSource("param[" + i + "]:" + typeName, -1));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Some methods may not have accessible args
        }
    }

    private List<Integer> findTaintedParamIndices(MethodNode mth, Set<SSAVar> taintedVars) {
        List<Integer> indices = new ArrayList<>();
        try {
            var args = mth.getArgRegs();
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    RegisterArg arg = args.get(i);
                    if (arg != null && arg.getSVar() != null && taintedVars.contains(arg.getSVar())) {
                        indices.add(i);
                    }
                }
            }
        } catch (Exception ignored) {}
        return indices;
    }

    private boolean isReturnTainted(MethodNode mth, Set<SSAVar> taintedVars) {
        try {
            InsnNode[] instructions = mth.getInstructions();
            if (instructions == null) return false;
            for (InsnNode insn : instructions) {
                if (insn != null && insn.getType() == InsnType.RETURN) {
                    for (int i = 0; i < insn.getArgsCount(); i++) {
                        InsnArg arg = insn.getArg(i);
                        if (arg instanceof RegisterArg) {
                            SSAVar svar = ((RegisterArg) arg).getSVar();
                            if (svar != null && taintedVars.contains(svar)) return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isSourceAPI(String normalizedId) {
        // Reuse SecurityAnnotator's source registry knowledge
        return normalizedId.contains("getStringExtra") ||
                normalizedId.contains("getIntExtra") ||
                normalizedId.contains("getData") ||
                normalizedId.contains("getExtras") ||
                normalizedId.contains("getQueryParameter") ||
                normalizedId.contains("getPath") ||
                normalizedId.contains("readLine") ||
                normalizedId.contains("getString") && normalizedId.contains("SharedPreferences") ||
                normalizedId.contains("getText") && normalizedId.contains("EditText") ||
                normalizedId.contains("getPrimaryClip") ||
                normalizedId.contains("openFileInput") ||
                normalizedId.contains("getIntent");
    }

    private boolean isSinkAPI(String normalizedId) {
        return normalizedId.contains("Cipher.getInstance") ||
                normalizedId.contains("loadUrl") ||
                normalizedId.contains("evaluateJavascript") ||
                normalizedId.contains("Runtime.exec") ||
                normalizedId.contains("rawQuery") ||
                normalizedId.contains("execSQL") ||
                normalizedId.contains("sendBroadcast") ||
                normalizedId.contains("startActivity") ||
                normalizedId.contains("FileOutputStream") ||
                normalizedId.contains("addJavascriptInterface") ||
                normalizedId.contains("DexClassLoader") ||
                normalizedId.contains("Method.invoke");
    }

    private String getSinkCategory(String normalizedId) {
        if (normalizedId.contains("Cipher") || normalizedId.contains("SecretKey")) return "crypto";
        if (normalizedId.contains("WebView") || normalizedId.contains("loadUrl") || normalizedId.contains("evaluateJavascript")) return "webview";
        if (normalizedId.contains("Runtime.exec") || normalizedId.contains("ProcessBuilder")) return "exec";
        if (normalizedId.contains("rawQuery") || normalizedId.contains("execSQL")) return "sql";
        if (normalizedId.contains("FileOutputStream") || normalizedId.contains("openFileOutput")) return "file";
        if (normalizedId.contains("sendBroadcast") || normalizedId.contains("startActivity")) return "intent";
        if (normalizedId.contains("DexClassLoader") || normalizedId.contains("Method.invoke")) return "dynamic_code";
        return "unknown";
    }

    private int getInsnLine(InsnNode insn) {
        try {
            return insn.getSourceLine();
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== Query API ====================

    public TaintResult getCachedResult(String methodKey) {
        return cache.get(methodKey);
    }

    public int getAnalyzedCount() {
        return analyzedCount.get();
    }

    public void clearCache() {
        cache.clear();
        analyzedCount.set(0);
    }

    // ==================== Inner types ====================

    public static class TaintResult {
        public String methodKey;
        public String className;
        public String methodName;
        public boolean isDeep;
        public List<TaintFlow> flows = new ArrayList<>();
        public List<Integer> taintedParams = new ArrayList<>();
        public boolean taintedReturn = false;
        public int internalFlows = 0;
        public int crossMethodFlows = 0;
        public String error;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", methodKey);
            // Go extractor compat: expose class_name and method_name at top level
            map.put("class_name", className);
            map.put("method_name", methodName);
            map.put("depth", isDeep ? "deep" : "shallow");

            List<Map<String, Object>> flowMaps = new ArrayList<>();
            for (TaintFlow flow : flows) {
                flowMaps.add(flow.toMap());
            }
            map.put("flows", flowMaps);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tainted_params", taintedParams);
            summary.put("tainted_return", taintedReturn);
            summary.put("internal_flows", internalFlows);
            summary.put("cross_method_flows", crossMethodFlows);
            map.put("method_summary", summary);

            if (error != null) {
                map.put("error", error);
            }

            return map;
        }
    }

    public static class TaintFlow {
        public TaintSource source;
        public TaintSink sink;
        public List<TaintStep> path = new ArrayList<>();
        public String taintType;     // "ssa_traced" or "cross_method_traced"
        public String confidence;    // "high", "medium", "low"

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source.toMap());
            map.put("sink", sink.toMap());

            List<Map<String, Object>> pathMaps = new ArrayList<>();
            for (TaintStep step : path) {
                pathMaps.add(step.toMap());
            }
            map.put("taint_path", pathMaps);
            map.put("taint_type", taintType);
            map.put("confidence", confidence);
            return map;
        }
    }

    public static class TaintSource {
        public String api;
        public int line;

        public TaintSource(String api, int line) {
            this.api = api;
            this.line = line;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("api", api);
            if (line >= 0) map.put("line", line);
            // Go extractor compat: parse class/method from API string
            if (api != null && api.contains(".")) {
                int lastDot = api.lastIndexOf('.');
                map.put("class", api.substring(0, lastDot));
                map.put("method", api.substring(lastDot + 1));
            }
            return map;
        }
    }

    public static class TaintSink {
        public String api;
        public int line;
        public String category;

        public TaintSink(String api, int line, String category) {
            this.api = api;
            this.line = line;
            this.category = category;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("api", api);
            if (line >= 0) map.put("line", line);
            map.put("category", category);
            // Go extractor compat: parse class/method from API string
            if (api != null && api.contains(".")) {
                int lastDot = api.lastIndexOf('.');
                map.put("class", api.substring(0, lastDot));
                map.put("method", api.substring(lastDot + 1));
            }
            return map;
        }
    }

    public static class TaintStep {
        public String var;
        public String insn;
        public int line;

        public TaintStep(String var, String insn, int line) {
            this.var = var;
            this.insn = insn;
            this.line = line;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("var", var);
            map.put("insn", insn);
            if (line >= 0) map.put("line", line);
            return map;
        }
    }
}
