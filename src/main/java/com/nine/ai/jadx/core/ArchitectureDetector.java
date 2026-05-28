package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Layer 2: 架构模式检测器。
 * 在 CodeIndex 遍历时统计各种架构特征的出现频率，最终推断 app 使用的架构模式。
 *
 * 检测模式：
 * - MVVM (ViewModel + LiveData/StateFlow + Repository)
 * - MVP (Presenter + View interface + Contract)
 * - Clean Architecture (domain/data/presentation layers)
 * - MVI (Intent/State + Reducer)
 * - Compose (Composable functions)
 * - Traditional (Activity/Fragment-centric, no clear separation)
 */
public class ArchitectureDetector {
    private static final Logger LOG = LoggerFactory.getLogger(ArchitectureDetector.class);

    // Signal counters
    private final AtomicInteger viewModelCount = new AtomicInteger(0);
    private final AtomicInteger liveDataCount = new AtomicInteger(0);
    private final AtomicInteger stateFlowCount = new AtomicInteger(0);
    private final AtomicInteger presenterCount = new AtomicInteger(0);
    private final AtomicInteger repositoryCount = new AtomicInteger(0);
    private final AtomicInteger useCaseCount = new AtomicInteger(0);
    private final AtomicInteger composableCount = new AtomicInteger(0);
    private final AtomicInteger mviIntentCount = new AtomicInteger(0);
    private final AtomicInteger daggerModuleCount = new AtomicInteger(0);
    private final AtomicInteger hiltAnnotationCount = new AtomicInteger(0);

    // Package structure signals
    private final Set<String> domainPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> dataPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> presentationPackages = ConcurrentHashMap.newKeySet();

    // Notable classes found
    private final Set<String> viewModels = ConcurrentHashMap.newKeySet();
    private final Set<String> presenters = ConcurrentHashMap.newKeySet();
    private final Set<String> repositories = ConcurrentHashMap.newKeySet();

    // Detection result (computed once after full scan)
    private volatile DetectionResult result = null;

    // ==================== Patterns ====================

    private static final Pattern VIEWMODEL_CLASS = Pattern.compile(
            "extends\\s+(?:Android)?ViewModel\\b");
    private static final Pattern LIVEDATA_USAGE = Pattern.compile(
            "\\bMutableLiveData\\b|\\bLiveData\\b|\\bobserve\\s*\\(");
    private static final Pattern STATEFLOW_USAGE = Pattern.compile(
            "\\bMutableStateFlow\\b|\\bStateFlow\\b|\\bcollect\\s*\\{");
    private static final Pattern PRESENTER_CLASS = Pattern.compile(
            "(?:class|interface)\\s+\\w*Presenter\\b|implements\\s+\\w*Presenter\\b");
    private static final Pattern REPOSITORY_CLASS = Pattern.compile(
            "(?:class|interface)\\s+\\w*Repository\\b");
    private static final Pattern USECASE_CLASS = Pattern.compile(
            "(?:class|interface)\\s+\\w*UseCase\\b|(?:class|interface)\\s+\\w*Interactor\\b");
    private static final Pattern COMPOSABLE_FUNCTION = Pattern.compile(
            "@Composable\\b");
    private static final Pattern MVI_PATTERN = Pattern.compile(
            "sealed\\s+(?:class|interface)\\s+\\w*(?:Intent|Action|Event)\\b|" +
            "sealed\\s+(?:class|interface)\\s+\\w*State\\b|" +
            "\\breduce\\s*\\(");
    private static final Pattern DAGGER_MODULE = Pattern.compile("@Module\\b");
    private static final Pattern HILT_ANNOTATION = Pattern.compile(
            "@HiltAndroidApp\\b|@AndroidEntryPoint\\b|@HiltViewModel\\b|@InstallIn\\b");

    // Package name patterns
    private static final Pattern DOMAIN_PACKAGE = Pattern.compile(
            "\\b(?:domain|usecase|interactor)\\b");
    private static final Pattern DATA_PACKAGE = Pattern.compile(
            "\\b(?:data|repository|datasource|remote|local|db|network|api)\\b");
    private static final Pattern PRESENTATION_PACKAGE = Pattern.compile(
            "\\b(?:presentation|ui|view|screen|feature)\\b");

    // ==================== Build API ====================

    /**
     * Analyze a class for architecture signals.
     * Called during CodeIndex traversal.
     *
     * @param className Full class name (e.g., "com.example.ui.MainViewModel")
     * @param sourceCode Decompiled source code
     */
    public void analyzeClass(String className, String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return;

        // Package structure
        String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
        if (DOMAIN_PACKAGE.matcher(pkg).find()) domainPackages.add(pkg);
        if (DATA_PACKAGE.matcher(pkg).find()) dataPackages.add(pkg);
        if (PRESENTATION_PACKAGE.matcher(pkg).find()) presentationPackages.add(pkg);

        // MVVM signals
        if (VIEWMODEL_CLASS.matcher(sourceCode).find()) {
            viewModelCount.incrementAndGet();
            viewModels.add(className);
        }
        if (LIVEDATA_USAGE.matcher(sourceCode).find()) {
            liveDataCount.incrementAndGet();
        }
        if (STATEFLOW_USAGE.matcher(sourceCode).find()) {
            stateFlowCount.incrementAndGet();
        }

        // MVP signals
        if (PRESENTER_CLASS.matcher(sourceCode).find()) {
            presenterCount.incrementAndGet();
            presenters.add(className);
        }

        // Repository pattern
        if (REPOSITORY_CLASS.matcher(sourceCode).find()) {
            repositoryCount.incrementAndGet();
            repositories.add(className);
        }

        // Clean Architecture signals
        if (USECASE_CLASS.matcher(sourceCode).find()) {
            useCaseCount.incrementAndGet();
        }

        // Compose
        if (COMPOSABLE_FUNCTION.matcher(sourceCode).find()) {
            composableCount.incrementAndGet();
        }

        // MVI
        if (MVI_PATTERN.matcher(sourceCode).find()) {
            mviIntentCount.incrementAndGet();
        }

        // DI
        if (DAGGER_MODULE.matcher(sourceCode).find()) {
            daggerModuleCount.incrementAndGet();
        }
        if (HILT_ANNOTATION.matcher(sourceCode).find()) {
            hiltAnnotationCount.incrementAndGet();
        }
    }

    /**
     * Finalize detection after all classes have been scanned.
     * Must be called after the full traversal completes.
     */
    public DetectionResult finalizeDetection() {
        DetectionResult r = new DetectionResult();

        // Score each pattern
        int mvvmScore = viewModelCount.get() * 3 + liveDataCount.get() + stateFlowCount.get() + repositoryCount.get();
        int mvpScore = presenterCount.get() * 3;
        int cleanScore = useCaseCount.get() * 3 + (domainPackages.isEmpty() ? 0 : 5) + (dataPackages.isEmpty() ? 0 : 3);
        int mviScore = mviIntentCount.get() * 3 + stateFlowCount.get();
        int composeScore = composableCount.get() * 2;

        // Determine primary pattern
        int maxScore = Math.max(Math.max(mvvmScore, mvpScore), Math.max(cleanScore, Math.max(mviScore, composeScore)));

        if (maxScore < 3) {
            r.primaryPattern = "traditional";
            r.confidence = "low";
        } else if (maxScore == mvvmScore) {
            r.primaryPattern = "mvvm";
            r.confidence = mvvmScore > 10 ? "high" : "medium";
        } else if (maxScore == mvpScore) {
            r.primaryPattern = "mvp";
            r.confidence = mvpScore > 10 ? "high" : "medium";
        } else if (maxScore == cleanScore) {
            r.primaryPattern = "clean_architecture";
            r.confidence = cleanScore > 10 ? "high" : "medium";
        } else if (maxScore == mviScore) {
            r.primaryPattern = "mvi";
            r.confidence = mviScore > 10 ? "high" : "medium";
        } else {
            r.primaryPattern = "compose";
            r.confidence = composeScore > 10 ? "high" : "medium";
        }

        // Secondary patterns
        r.secondaryPatterns = new ArrayList<>();
        if (cleanScore > 3 && !r.primaryPattern.equals("clean_architecture")) {
            r.secondaryPatterns.add("clean_architecture");
        }
        if (composeScore > 3 && !r.primaryPattern.equals("compose")) {
            r.secondaryPatterns.add("compose_ui");
        }

        // DI framework
        if (hiltAnnotationCount.get() > 0) {
            r.diFramework = "hilt";
        } else if (daggerModuleCount.get() > 0) {
            r.diFramework = "dagger";
        } else {
            r.diFramework = "none_detected";
        }

        // Signals breakdown
        r.signals = new LinkedHashMap<>();
        r.signals.put("viewmodels", viewModelCount.get());
        r.signals.put("livedata_usage", liveDataCount.get());
        r.signals.put("stateflow_usage", stateFlowCount.get());
        r.signals.put("presenters", presenterCount.get());
        r.signals.put("repositories", repositoryCount.get());
        r.signals.put("use_cases", useCaseCount.get());
        r.signals.put("composables", composableCount.get());
        r.signals.put("mvi_patterns", mviIntentCount.get());

        // Package layers
        r.packageLayers = new LinkedHashMap<>();
        if (!domainPackages.isEmpty()) r.packageLayers.put("domain", new ArrayList<>(domainPackages));
        if (!dataPackages.isEmpty()) r.packageLayers.put("data", new ArrayList<>(dataPackages));
        if (!presentationPackages.isEmpty()) r.packageLayers.put("presentation", new ArrayList<>(presentationPackages));

        // Notable classes
        r.viewModels = new ArrayList<>(viewModels);
        r.presenters = new ArrayList<>(presenters);
        r.repositories = new ArrayList<>(repositories);

        this.result = r;
        LOG.info("Architecture detected: {} (confidence: {}, DI: {})",
                r.primaryPattern, r.confidence, r.diFramework);

        return r;
    }

    // ==================== Query API ====================

    public DetectionResult getResult() {
        return result;
    }

    public Map<String, Object> getResultAsMap() {
        DetectionResult r = result;
        if (r == null) return Map.of("pattern", "not_analyzed");
        return r.toMap();
    }

    public void clear() {
        viewModelCount.set(0);
        liveDataCount.set(0);
        stateFlowCount.set(0);
        presenterCount.set(0);
        repositoryCount.set(0);
        useCaseCount.set(0);
        composableCount.set(0);
        mviIntentCount.set(0);
        daggerModuleCount.set(0);
        hiltAnnotationCount.set(0);
        domainPackages.clear();
        dataPackages.clear();
        presentationPackages.clear();
        viewModels.clear();
        presenters.clear();
        repositories.clear();
        result = null;
    }

    // ==================== Inner type ====================

    public static class DetectionResult {
        public String primaryPattern;
        public String confidence;
        public List<String> secondaryPatterns;
        public String diFramework;
        public Map<String, Integer> signals;
        public Map<String, List<String>> packageLayers;
        public List<String> viewModels;
        public List<String> presenters;
        public List<String> repositories;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("primary_pattern", primaryPattern);
            map.put("confidence", confidence);
            map.put("secondary_patterns", secondaryPatterns);
            map.put("di_framework", diFramework);
            map.put("signals", signals);
            if (packageLayers != null && !packageLayers.isEmpty()) {
                map.put("package_layers", packageLayers);
            }
            if (viewModels != null && !viewModels.isEmpty()) {
                map.put("viewmodels", viewModels.size() > 20 ? viewModels.subList(0, 20) : viewModels);
            }
            if (repositories != null && !repositories.isEmpty()) {
                map.put("repositories", repositories);
            }
            return map;
        }
    }
}
