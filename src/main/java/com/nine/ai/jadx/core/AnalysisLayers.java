package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分层分析基础设施的状态管理器。
 * 管理 5 层分析引擎的就绪状态和构建进度。
 *
 * Layer 0: Manifest + ApkOverview + EntryPoints   (<1s, 同步)
 * Layer 1: CodeIndex + StringConstantIndex + LibraryID  (10-60s, 异步)
 * Layer 2: CallGraph + SecurityAnnotator + ApiEndpoints + DI + Architecture  (+0s, 搭Layer1便车)
 * Layer 3: RuleEngine YAML 扫描  (<2s, Layer1+2完成后触发)
 * Layer 4: SSATaintAnalyzer  (按需, 不预热)
 */
public class AnalysisLayers {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisLayers.class);

    public static final int LAYER_COUNT = 5;

    public enum LayerState {
        NOT_STARTED,
        BUILDING,
        READY,
        FAILED
    }

    private final AtomicReference<LayerState>[] states;
    private final AtomicInteger[] progress; // 0-100
    private final String[] layerNames = {
            "manifest",
            "code_index",
            "call_graph",
            "rule_engine",
            "ssa_taint"
    };

    // Layer-specific stats
    private volatile int classesIndexed = 0;
    private volatile int stringsIndexed = 0;
    private volatile int callGraphEdges = 0;
    private volatile int sinksFound = 0;
    private volatile int sourcesFound = 0;
    private volatile int apiEndpoints = 0;
    private volatile int diBindings = 0;
    private volatile int rulesLoaded = 0;
    private volatile int ruleFindings = 0;
    private volatile int taintCachedMethods = 0;
    private volatile int thirdPartyClasses = 0;
    private volatile int appClasses = 0;

    @SuppressWarnings("unchecked")
    public AnalysisLayers() {
        states = new AtomicReference[LAYER_COUNT];
        progress = new AtomicInteger[LAYER_COUNT];
        for (int i = 0; i < LAYER_COUNT; i++) {
            states[i] = new AtomicReference<>(LayerState.NOT_STARTED);
            progress[i] = new AtomicInteger(0);
        }
        // Layer 4 is always ON_DEMAND
        states[4].set(LayerState.READY);
    }

    // ==================== State transitions ====================

    public void markBuilding(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return;
        states[layer].set(LayerState.BUILDING);
        LOG.info("Layer {} ({}) → BUILDING", layer, layerNames[layer]);
    }

    public void markReady(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return;
        states[layer].set(LayerState.READY);
        progress[layer].set(100);
        LOG.info("Layer {} ({}) → READY", layer, layerNames[layer]);
    }

    public void markFailed(int layer, Throwable cause) {
        if (layer < 0 || layer >= LAYER_COUNT) return;
        states[layer].set(LayerState.FAILED);
        LOG.error("Layer {} ({}) → FAILED: {}", layer, layerNames[layer], cause.getMessage());
    }

    public void updateProgress(int layer, int percent) {
        if (layer < 0 || layer >= LAYER_COUNT) return;
        progress[layer].set(Math.min(100, Math.max(0, percent)));
    }

    // ==================== Queries ====================

    public boolean isReady(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return false;
        return states[layer].get() == LayerState.READY;
    }

    public LayerState getState(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return LayerState.NOT_STARTED;
        return states[layer].get();
    }

    public int getProgress(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) return 0;
        return progress[layer].get();
    }

    /**
     * Check if all layers up to (inclusive) the given layer are ready.
     */
    public boolean isLayerChainReady(int upToLayer) {
        for (int i = 0; i <= Math.min(upToLayer, LAYER_COUNT - 1); i++) {
            if (i == 4) continue; // Layer 4 is on-demand
            if (states[i].get() != LayerState.READY) return false;
        }
        return true;
    }

    // ==================== Stats setters ====================

    public void setCodeIndexStats(int classes, int strings, int thirdParty, int app) {
        this.classesIndexed = classes;
        this.stringsIndexed = strings;
        this.thirdPartyClasses = thirdParty;
        this.appClasses = app;
    }

    public void setCallGraphStats(int edges, int sinks, int sources, int endpoints, int di) {
        this.callGraphEdges = edges;
        this.sinksFound = sinks;
        this.sourcesFound = sources;
        this.apiEndpoints = endpoints;
        this.diBindings = di;
    }

    public void setRuleEngineStats(int loaded, int findings) {
        this.rulesLoaded = loaded;
        this.ruleFindings = findings;
    }

    public void incrementTaintCache() {
        this.taintCachedMethods++;
    }

    // ==================== Status report ====================

    /**
     * Build a full status report for /system?action=status
     */
    public Map<String, Object> getStatusReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        Map<String, Object> l0 = layerReport(0);
        report.put("layer_0_manifest", l0);

        Map<String, Object> l1 = layerReport(1);
        l1.put("classes_indexed", classesIndexed);
        l1.put("strings_indexed", stringsIndexed);
        report.put("layer_1_code_index", l1);

        Map<String, Object> l2 = layerReport(2);
        l2.put("edges", callGraphEdges);
        l2.put("sinks", sinksFound);
        l2.put("sources", sourcesFound);
        l2.put("api_endpoints", apiEndpoints);
        l2.put("di_bindings", diBindings);
        report.put("layer_2_call_graph", l2);

        Map<String, Object> l3 = layerReport(3);
        l3.put("rules_loaded", rulesLoaded);
        l3.put("findings", ruleFindings);
        report.put("layer_3_rule_engine", l3);

        Map<String, Object> l4 = new LinkedHashMap<>();
        l4.put("state", "ON_DEMAND");
        l4.put("cached_methods", taintCachedMethods);
        report.put("layer_4_ssa_taint", l4);

        return report;
    }

    private Map<String, Object> layerReport(int layer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", states[layer].get().name());
        m.put("progress", progress[layer].get());
        return m;
    }

    public int getThirdPartyClasses() { return thirdPartyClasses; }
    public int getAppClasses() { return appClasses; }

    // ==================== Reset ====================

    public void reset() {
        for (int i = 0; i < LAYER_COUNT; i++) {
            states[i].set(LayerState.NOT_STARTED);
            progress[i].set(0);
        }
        states[4].set(LayerState.READY);
        classesIndexed = stringsIndexed = 0;
        callGraphEdges = sinksFound = sourcesFound = apiEndpoints = diBindings = 0;
        rulesLoaded = ruleFindings = taintCachedMethods = 0;
        thirdPartyClasses = appClasses = 0;
    }
}
