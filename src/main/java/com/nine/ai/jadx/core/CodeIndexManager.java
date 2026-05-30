package com.nine.ai.jadx.core;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.instructions.InsnType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 增强版代码索引管理器（Layer 1+2 核心）。
 *
 * 在 parallelStream 遍历所有类时，一次性构建：
 * 1. Code Index: className → source code
 * 2. Third-party library identification (包名前缀匹配)
 * 3. CallGraph: 双向调用图 (InvokeNode 扫描)
 * 4. SecurityAnnotator: sink/source 安全标注
 * 5. ApiEndpointIndex: Retrofit/OkHttp 端点提取
 * 6. DIBindingResolver: Dagger/Hilt DI 绑定解析
 * 7. ArchitectureDetector: 架构模式检测
 *
 * Layer 1 + Layer 2 共享同一次遍历，零额外 IO 开销。
 */
public class CodeIndexManager {
    private static final Logger LOG = LoggerFactory.getLogger(CodeIndexManager.class);
    private static final CodeIndexManager INSTANCE = new CodeIndexManager();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, String> codeIndex = null;
    private volatile boolean indexed = false;
    private final AtomicInteger indexedCount = new AtomicInteger(0);
    private volatile int totalClasses = 0;

    // Third-party library identification
    private final Set<String> thirdPartyClasses = ConcurrentHashMap.newKeySet();
    private final Map<String, String> libraryMapping = new ConcurrentHashMap<>(); // className → libraryName
    private final Set<String> detectedLibraries = ConcurrentHashMap.newKeySet();

    // Layer 2 engines (built during the same traversal)
    private final CallGraph callGraph = new CallGraph();
    private final SecurityAnnotator securityAnnotator = new SecurityAnnotator();
    private final ApiEndpointIndex apiEndpointIndex = new ApiEndpointIndex();
    private final DIBindingResolver diBindingResolver = new DIBindingResolver();
    private final ArchitectureDetector architectureDetector = new ArchitectureDetector();

    // Layer 1 string constant index (built during same traversal)
    private final StringConstantIndex stringConstantIndex = new StringConstantIndex();

    // Layer 3 engine
    private final RuleEngine ruleEngine = new RuleEngine();

    // Layer 4 engine (lazy init - needs callGraph + securityAnnotator)
    private volatile SSATaintAnalyzer ssaTaintAnalyzer;

    // Known library prefixes
    private static final Map<String, String> KNOWN_LIBRARIES = Map.ofEntries(
            Map.entry("com.google.firebase", "Firebase"),
            Map.entry("com.google.android.gms", "Google Play Services"),
            Map.entry("com.google.android.material", "Material Design"),
            Map.entry("com.google.gson", "Gson"),
            Map.entry("com.google.protobuf", "Protocol Buffers"),
            Map.entry("com.squareup.okhttp3", "OkHttp"),
            Map.entry("com.squareup.okio", "Okio"),
            Map.entry("com.squareup.picasso", "Picasso"),
            Map.entry("com.squareup.moshi", "Moshi"),
            Map.entry("retrofit2", "Retrofit"),
            Map.entry("dagger", "Dagger"),
            Map.entry("io.reactivex", "RxJava"),
            Map.entry("kotlinx.coroutines", "Coroutines"),
            Map.entry("kotlinx.serialization", "Kotlin Serialization"),
            Map.entry("kotlin.coroutines", "Kotlin Coroutines"),
            Map.entry("com.facebook", "Facebook SDK"),
            Map.entry("com.airbnb.lottie", "Lottie"),
            Map.entry("com.bumptech.glide", "Glide"),
            Map.entry("org.greenrobot.eventbus", "EventBus"),
            Map.entry("com.fasterxml.jackson", "Jackson"),
            Map.entry("org.apache.commons", "Apache Commons"),
            Map.entry("com.github.bumptech", "Glide"),
            Map.entry("io.realm", "Realm"),
            Map.entry("org.jetbrains.annotations", "JetBrains Annotations"),
            Map.entry("androidx", "AndroidX"),
            Map.entry("android.support", "Android Support Library"),
            Map.entry("com.crashlytics", "Crashlytics"),
            Map.entry("io.sentry", "Sentry"),
            Map.entry("com.adjust.sdk", "Adjust"),
            Map.entry("com.appsflyer", "AppsFlyer"),
            Map.entry("org.bouncycastle", "Bouncy Castle"),
            Map.entry("okhttp3", "OkHttp"),
            Map.entry("com.jakewharton", "Jake Wharton Libraries"),
            Map.entry("timber.log", "Timber"),
            Map.entry("com.tencent", "Tencent SDK"),
            Map.entry("com.alibaba", "Alibaba SDK"),
            Map.entry("com.bytedance", "ByteDance SDK"),
            Map.entry("org.conscrypt", "Conscrypt"),
            Map.entry("com.android.volley", "Volley"),
            Map.entry("org.json", "org.json")
    );

    private CodeIndexManager() {}

    public static CodeIndexManager getInstance() {
        return INSTANCE;
    }

    /**
     * Build the full code index. Thread-safe with read-write locking.
     * Returns the code index map (className → source code).
     */
    public Map<String, String> buildIndex(JadxDecompiler decompiler, AnalysisLayers layers) {
        lock.readLock().lock();
        try {
            if (indexed && codeIndex != null) {
                return codeIndex;
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            if (indexed && codeIndex != null) {
                return codeIndex;
            }

            // Clear any partial state from a previous failed attempt
            if (codeIndex != null) codeIndex.clear();
            thirdPartyClasses.clear();
            libraryMapping.clear();
            detectedLibraries.clear();
            callGraph.clear();
            securityAnnotator.clear();
            apiEndpointIndex.clear();
            diBindingResolver.clear();
            architectureDetector.clear();
            stringConstantIndex.clear();

            layers.markBuilding(1);
            long start = System.currentTimeMillis();
            codeIndex = new ConcurrentHashMap<>();

            // Defensive copy: getClassesWithInners() returns a view over JADX's internal list
            // which may still be mutated by background threads. Copy to our own ArrayList.
            var rawList = decompiler.getClassesWithInners();
            var classList = new java.util.ArrayList<>(rawList);
            totalClasses = classList.size();
            indexedCount.set(0);

            // Parallel build: code index + library identification
            classList.parallelStream().forEach(cls -> {
                try {
                    String fullName = cls.getFullName();

                    // Library identification
                    String lib = identifyLibrary(fullName);
                    if (lib != null) {
                        thirdPartyClasses.add(fullName);
                        libraryMapping.put(fullName, lib);
                        detectedLibraries.add(lib);
                        // Still index the code (might be needed for specific queries)
                    }

                    // === Layer 2: IR scan via ClassNode (NO decompilation trigger) ===
                    // IMPORTANT: We use cls.getClassNode().getMethods() instead of
                    // cls.getMethods(). The latter calls JavaClass.load() which triggers
                    // full decompilation → InlineMethods.forceProcess() → unload(),
                    // causing JadxRuntimeException for complex classes and wiping IR.
                    // ClassNode.getMethods() returns raw MethodNodes without decompiling.

                    if (lib == null) {
                        try {
                            ClassNode classNode = cls.getClassNode();
                            for (MethodNode mth : classNode.getMethods()) {
                                try {
                                    String methodName = mth.getName();
                                    if (methodName == null) continue;
                                    String methodKey = fullName + "#" + methodName;

                                    // Load raw DEX instructions into IR
                                    try {
                                        mth.load();
                                    } catch (Exception ignored) {
                                        continue; // skip methods whose IR cannot be loaded
                                    }

                                    // Walk instructions looking for InvokeNodes
                                    InsnNode[] instructions = mth.getInstructions();
                                    if (instructions != null) {
                                        for (InsnNode insn : instructions) {
                                            if (insn == null) continue;
                                            if (insn.getType() == InsnType.INVOKE) {
                                                try {
                                                    InvokeNode invoke = (InvokeNode) insn;
                                                    var callMth = invoke.getCallMth();
                                                    if (callMth == null) continue;
                                                    String callTarget = callMth.toString();

                                                    callGraph.addEdge(methodKey, callTarget);
                                                    securityAnnotator.recordInvoke(methodKey, fullName, callTarget);
                                                } catch (Exception ignored) {
                                                    // Malformed invoke instruction; skip
                                                }
                                            }
                                        }
                                    }

                                    // Free IR memory immediately after scanning
                                    try { mth.unload(); } catch (Exception ignored) {}
                                } catch (Exception ignored) {
                                    // Single method failure; continue with next
                                }
                            }
                        } catch (Throwable e) {
                            // ClassNode access or IR iteration failure; non-fatal
                        }
                    }

                    // Code indexing — getCode() triggers decompile (separate from IR scan)
                    String code = null;
                    try {
                        code = cls.getCode();
                    } catch (Throwable e) {
                        // Decompilation can fail for some classes; non-fatal
                    }
                    if (code != null && !code.isEmpty()) {
                        codeIndex.put(fullName, code);
                    }

                    // === Layer 2: source-code-based analysis (uses decompiled text) ===
                    // Each analysis is wrapped independently so one failure doesn't skip others

                    if (lib == null && code != null) {
                        try { apiEndpointIndex.scanClass(fullName, code); }
                        catch (Exception ignored) {}

                        try { securityAnnotator.scanProtocolFields(fullName, code); }
                        catch (Exception ignored) {}

                        try { diBindingResolver.scanClass(fullName, code); }
                        catch (Exception ignored) {}

                        try { architectureDetector.analyzeClass(fullName, code); }
                        catch (Exception ignored) {}

                        try { stringConstantIndex.scanClass(fullName, code); }
                        catch (Exception ignored) {}
                    }

                } catch (Throwable ignored) {
                    // Single class failure should not break the whole index
                } finally {
                    int done = indexedCount.incrementAndGet();
                    if (done % 1000 == 0) {
                        layers.updateProgress(1, (int) ((done * 100L) / totalClasses));
                    }
                }
            });

            indexed = true;
            long elapsed = System.currentTimeMillis() - start;

            int appCount = codeIndex.size() - thirdPartyClasses.size();
            layers.setCodeIndexStats(codeIndex.size(), stringConstantIndex.getTotalStrings(), thirdPartyClasses.size(), appCount);
            layers.markReady(1);

            // Finalize Layer 2 engines
            securityAnnotator.finalizeBuild();
            ArchitectureDetector.DetectionResult archResult = architectureDetector.finalizeDetection();

            layers.setCallGraphStats(
                    callGraph.getEdgeCount(),
                    securityAnnotator.getTotalSinks(),
                    securityAnnotator.getTotalSources(),
                    apiEndpointIndex.getTotalEndpoints(),
                    diBindingResolver.getBindingsAsMap().size()
            );
            layers.markReady(2);

            LOG.info("Code index built: {} classes ({} app, {} third-party, {} libraries), {} strings in {}ms",
                    codeIndex.size(), appCount, thirdPartyClasses.size(), detectedLibraries.size(),
                    stringConstantIndex.getTotalStrings(), elapsed);
            LOG.info("Layer 2: {} call edges, {} sinks, {} sources, {} endpoints, arch={}",
                    callGraph.getEdgeCount(), securityAnnotator.getTotalSinks(),
                    securityAnnotator.getTotalSources(), apiEndpointIndex.getTotalEndpoints(),
                    archResult != null ? archResult.primaryPattern : "unknown");

            return codeIndex;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get code index (returns null if not built yet).
     */
    public Map<String, String> getCodeIndex() {
        lock.readLock().lock();
        try {
            return codeIndex;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isIndexed() {
        lock.readLock().lock();
        try {
            return indexed;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getProgress() {
        if (indexed) return 100;
        if (totalClasses == 0) return -1;
        return (int) ((indexedCount.get() * 100L) / totalClasses);
    }

    // ==================== Library identification ====================

    public boolean isThirdParty(String className) {
        return thirdPartyClasses.contains(className);
    }

    public List<String> getDetectedLibraries() {
        return new ArrayList<>(detectedLibraries);
    }

    public int getThirdPartyCount() {
        return thirdPartyClasses.size();
    }

    public int getAppClassCount() {
        return codeIndex != null ? codeIndex.size() - thirdPartyClasses.size() : 0;
    }

    private String identifyLibrary(String className) {
        for (Map.Entry<String, String> entry : KNOWN_LIBRARIES.entrySet()) {
            if (className.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ==================== Layer 2 accessors ====================

    public CallGraph getCallGraph() {
        return callGraph;
    }

    public SecurityAnnotator getSecurityAnnotator() {
        return securityAnnotator;
    }

    public ApiEndpointIndex getApiEndpointIndex() {
        return apiEndpointIndex;
    }

    public DIBindingResolver getDIBindingResolver() {
        return diBindingResolver;
    }

    public ArchitectureDetector getArchitectureDetector() {
        return architectureDetector;
    }

    public StringConstantIndex getStringConstantIndex() {
        return stringConstantIndex;
    }

    public RuleEngine getRuleEngine() {
        return ruleEngine;
    }

    public SSATaintAnalyzer getSSATaintAnalyzer() {
        if (ssaTaintAnalyzer == null) {
            synchronized (this) {
                if (ssaTaintAnalyzer == null) {
                    ssaTaintAnalyzer = new SSATaintAnalyzer(callGraph, securityAnnotator);
                }
            }
        }
        return ssaTaintAnalyzer;
    }

    // ==================== Memory management ====================

    public void invalidate() {
        lock.writeLock().lock();
        try {
            indexed = false;
            if (codeIndex != null) {
                codeIndex.clear();
                codeIndex = null;
            }
            thirdPartyClasses.clear();
            libraryMapping.clear();
            detectedLibraries.clear();
            indexedCount.set(0);
            totalClasses = 0;

            // Clear Layer 2 engines
            callGraph.clear();
            securityAnnotator.clear();
            apiEndpointIndex.clear();
            diBindingResolver.clear();
            architectureDetector.clear();
            stringConstantIndex.clear();

            // Clear Layer 3 engine
            ruleEngine.clearFindings();

            // Clear Layer 4 engine
            if (ssaTaintAnalyzer != null) {
                ssaTaintAnalyzer.clearCache();
            }

            LOG.info("Code index invalidated (including Layer 2 engines)");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void trimIfNeeded() {
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        double usage = (usedMem * 100.0) / maxMem;

        if (usage > 85 && indexed) {
            LOG.warn("Memory pressure critical ({}%), invalidating code index", String.format("%.1f", usage));
            invalidate();
            System.gc();
        }
    }
}
