package com.nine.ai.jadx.server;

import com.nine.ai.jadx.core.AnalysisLayers;
import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.core.EntryPointCollector;
import com.nine.ai.jadx.core.ManifestAnalyzer;
import com.nine.ai.jadx.server.handler.*;
import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JADX Agent Plugin HTTP Server (redesigned).
 *
 * 4 routes:
 *   /code    → CodeHandler    (getClass, getMethod, batchGetClass)
 *   /search  → SearchHandler  (find, scan, findSinkSource)
 *   /analyze → AnalyzeHandler (component, callChain, dataFlow, entryPoints, attackSurface, resolveDI)
 *   /system  → SystemHandler  (status, overview, rename, clearCache, reloadRules)
 *
 * Startup pipeline:
 *   1. HTTP server starts immediately (status endpoint available)
 *   2. Background thread runs Layer 0 → Layer 1 → Layer 2 → Layer 3
 *   3. Each layer reports progress via AnalysisLayers
 */
public class PluginServer {
    private static final Logger LOG = LoggerFactory.getLogger(PluginServer.class);
    private static final int PORT = 13997;
    private static final int BACKLOG = 10;
    private static final int THREAD_POOL_SIZE = 10;
    private static final int ASYNC_POOL_SIZE = 4;

    private static PluginServer instance;
    private final JadxGuiContext guiContext;
    private final MainWindow mainWindow;

    private HttpServer server;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private long startTime = 0;

    // Core infrastructure
    private final AnalysisLayers layers = new AnalysisLayers();
    private final CodeIndexManager codeIndex = CodeIndexManager.getInstance();
    private final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
    private final EntryPointCollector entryPointCollector = new EntryPointCollector();

    /** Async pool for background analysis tasks */
    private static final ExecutorService ASYNC_POOL;
    static {
        AtomicInteger asyncThreadCount = new AtomicInteger();
        ASYNC_POOL = Executors.newFixedThreadPool(ASYNC_POOL_SIZE, r -> {
            Thread t = new Thread(r, "jadx-analysis-" + asyncThreadCount.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public static ExecutorService getAsyncPool() {
        return ASYNC_POOL;
    }

    private PluginServer(JadxGuiContext guiContext, MainWindow mainWindow) {
        this.guiContext = guiContext;
        this.mainWindow = mainWindow;
    }

    public static synchronized PluginServer getInstance(JadxGuiContext guiContext, MainWindow mainWindow) {
        if (instance == null) {
            instance = new PluginServer(guiContext, mainWindow);
        }
        return instance;
    }

    public static PluginServer getInstance() {
        return instance;
    }

    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            LOG.info("Server is already running");
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(PORT), BACKLOG);

            AtomicInteger threadCount = new AtomicInteger();
            server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
                Thread t = new Thread(r, "jadx-http-" + threadCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            }));

            // Register 4 routes
            route("/code", new CodeHandler(layers, codeIndex));
            route("/search", new SearchHandler(layers, codeIndex));
            route("/analyze", new AnalyzeHandler(layers, codeIndex, manifestAnalyzer));
            route("/system", new SystemHandler(layers, codeIndex, mainWindow, System.currentTimeMillis(), manifestAnalyzer));

            server.start();
            this.startTime = System.currentTimeMillis();
            LOG.info("JADX Agent Server started on port {} (4 routes: /code, /search, /analyze, /system)", PORT);

            // Launch background analysis pipeline
            startAnalysisPipeline();

        } catch (IOException e) {
            LOG.error("Failed to start server", e);
            isRunning.set(false);
            throw new RuntimeException("Server startup failed", e);
        }
    }

    /**
     * Background analysis pipeline.
     * Runs Layer 0 → Layer 1+2 → Layer 3 sequentially.
     */
    private void startAnalysisPipeline() {
        Thread pipeline = new Thread(() -> {
            try {
                // Wait for decompiler to be ready
                JadxDecompiler decompiler = waitForDecompiler(30_000);
                if (decompiler == null) {
                    LOG.error("Decompiler not available after 30s. Analysis pipeline aborted.");
                    return;
                }

                // Layer 0: Manifest + Overview (fast, <1s)
                LOG.info("Pipeline: Starting Layer 0 (Manifest)...");
                layers.markBuilding(0);
                try {
                    manifestAnalyzer.parse(decompiler);
                    entryPointCollector.collect(manifestAnalyzer);
                    layers.markReady(0);
                    LOG.info("Pipeline: Layer 0 complete (package={}, components={}, exported={}, entry_points={})",
                            manifestAnalyzer.getPackageName(),
                            manifestAnalyzer.getTotalComponents(),
                            manifestAnalyzer.getExportedCount(),
                            entryPointCollector.getTotalEntryPoints());
                } catch (Exception e) {
                    layers.markFailed(0, e);
                    return; // Cannot proceed without manifest
                }

                // Layer 1+2: Code Index + CallGraph + SecurityAnnotator (10-60s)
                LOG.info("Pipeline: Starting Layer 1+2 (CodeIndex + CallGraph)...");
                try {
                    codeIndex.buildIndex(decompiler, layers);
                    // Register entry points in CallGraph for reachability analysis
                    entryPointCollector.registerInCallGraph(codeIndex.getCallGraph());
                    LOG.info("Pipeline: Layer 1+2 complete");
                } catch (Exception e) {
                    layers.markFailed(1, e);
                    LOG.error("Pipeline: Layer 1+2 failed", e);
                    return;
                }

                // Layer 3: Rule Engine scan (requires Layer 1+2, <2s)
                LOG.info("Pipeline: Starting Layer 3 (RuleEngine)...");
                layers.markBuilding(3);
                try {
                    var ruleEngine = codeIndex.getRuleEngine();
                    int rulesLoaded = ruleEngine.loadBundledRules();
                    var scanResult = ruleEngine.scanAll(
                            codeIndex.getCodeIndex(),
                            codeIndex.getCallGraph(),
                            codeIndex.getSecurityAnnotator()
                    );
                    // Also scan manifest for manifest_check rules
                    ruleEngine.scanManifest(manifestAnalyzer);
                    layers.setRuleEngineStats(rulesLoaded, ruleEngine.getFindings(null, "info").size());
                    layers.markReady(3);
                    LOG.info("Pipeline: Layer 3 complete ({} rules, {} findings)", rulesLoaded, scanResult.totalFindings);
                } catch (Exception e) {
                    layers.markFailed(3, e);
                    LOG.warn("Pipeline: Layer 3 failed (non-critical)", e);
                }

                LOG.info("Pipeline: All layers ready. Analysis infrastructure fully operational.");

            } catch (Exception e) {
                LOG.error("Analysis pipeline failed unexpectedly", e);
            }
        }, "jadx-analysis-pipeline");
        pipeline.setDaemon(true);
        pipeline.start();
    }

    /**
     * Wait for decompiler to become available (it may take time after JADX GUI loads an APK).
     */
    private JadxDecompiler waitForDecompiler(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JadxDecompiler d = JadxUtil.getDecompiler(false);
            if (d != null) return d;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    public void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        LOG.info("Stopping JADX Agent Server...");
        ASYNC_POOL.shutdownNow();
        try {
            ASYNC_POOL.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (server != null) {
            server.stop(2);
            server = null;
        }
        this.startTime = 0;
    }

    // ==================== Route registration ====================

    private void route(String path, HttpHandler handler) {
        server.createContext(path, wrap(handler));
    }

    private HttpHandler wrap(HttpHandler handler) {
        HttpUtil http = HttpUtil.getInstance();
        return exchange -> {
            try {
                // CORS headers
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                handler.handle(exchange);
            } catch (Exception e) {
                LOG.error("Handler error: {}", exchange.getRequestURI(), e);
                try {
                    http.sendError(exchange, 500, "Internal Server Error");
                } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        };
    }

    // ==================== Accessors ====================

    public long getStartTime() { return startTime; }
    public JadxGuiContext getGuiContext() { return guiContext; }
    public boolean isRunning() { return isRunning.get(); }
    public AnalysisLayers getLayers() { return layers; }
    public CodeIndexManager getCodeIndex() { return codeIndex; }
}
