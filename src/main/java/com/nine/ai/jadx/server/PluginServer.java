package com.nine.ai.jadx.server;

import com.nine.ai.jadx.server.handler.rename.*;
import com.nine.ai.jadx.server.handler.basic.*;
import com.nine.ai.jadx.server.handler.code.*;
import com.nine.ai.jadx.server.handler.resource.*;
import com.nine.ai.jadx.server.handler.search.*;
import com.nine.ai.jadx.server.handler.xrefs.XrefsHandler;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nine.ai.jadx.util.CodeIndexManager;
import com.nine.ai.jadx.util.JadxUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件HTTP服务器核心类
 * 负责：生命周期管理、路由注册、全局 CORS 注入、线程调度
 */
public class PluginServer {
	private static final Logger LOG = LoggerFactory.getLogger(PluginServer.class);
	private static final int PORT = 13997;
	private static final int BACKLOG = 10;
	private static final int THREAD_POOL_SIZE = 10;
	private static final int ASYNC_POOL_SIZE = 6;

	private static PluginServer instance;
	private final JadxGuiContext guiContext;
	private final MainWindow mainWindow;

	private HttpServer server;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private long startTime = 0;
	private ApkOverviewHandler apkOverviewHandler;
	private ResourceExplorerHandler resourceExplorerHandler;

	/** 专用异步任务线程池，供搜索/扫描等耗时操作使用 */
	private static final ExecutorService ASYNC_POOL;
	static {
		AtomicInteger asyncThreadCount = new AtomicInteger();
		ASYNC_POOL = Executors.newFixedThreadPool(ASYNC_POOL_SIZE, r -> {
			Thread t = new Thread(r, "jadx-async-task-" + asyncThreadCount.incrementAndGet());
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
			LOG.info("Server is already running...");
			return;
		}

		try {
			server = HttpServer.create(new InetSocketAddress(PORT), BACKLOG);

			AtomicInteger threadCount = new AtomicInteger();
			server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
				Thread t = new Thread(r, "jadx-agent-http-" + threadCount.incrementAndGet());
				t.setDaemon(true);
				return t;
			}));

			// 路由注册
			route("/codeInsight", new CodeInsightHandler());
			resourceExplorerHandler = new ResourceExplorerHandler();
			route("/resourceExplorer", resourceExplorerHandler);
			route("/searchEngine", new SearchEngineHandler());
			route("/getXrefs", new XrefsHandler());
			route("/refactor", new RefactorHandler(mainWindow));

			SystemManagerHandler systemManagerHandler = new SystemManagerHandler();
			this.apkOverviewHandler = systemManagerHandler.getApkOverviewHandler();
			route("/systemManager", systemManagerHandler);

			server.start();
			this.startTime = System.currentTimeMillis();
			LOG.info("JADX Agent Server started on port {}", PORT);

			Thread preloadThread = new Thread(() -> {
				try {
					apkOverviewHandler.preload();
				} catch (Exception e) {
					LOG.warn("APK overview preload failed, will rebuild on first request", e);
				}
				try {
					resourceExplorerHandler.getManifestSummaryHandler().preload();
				} catch (Exception e) {
					LOG.warn("Manifest summary preload failed, will lazy-load on first request", e);
				}
				try {
					var decompiler = JadxUtil.getDecompiler();
					if (decompiler != null) {
						LOG.info("Starting code index pre-build...");
						CodeIndexManager.getInstance().getIndex(decompiler);
						LOG.info("Code index pre-build completed");
					}
				} catch (Exception e) {
					LOG.warn("Code index pre-build failed, will build on first search", e);
				}
			}, "jadx-preload");
			preloadThread.setDaemon(true);
			preloadThread.start();

		} catch (IOException e) {
			LOG.error("Failed to start server", e);
			isRunning.set(false);
			throw new RuntimeException("Server startup failed", e);
		}
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

	// ====================== 工具方法 ======================

	private void route(String path, HttpHandler handler) {
		server.createContext(path, wrap(handler));
	}

	private HttpHandler wrap(HttpHandler handler) {
		HttpUtil http = HttpUtil.getInstance();
		return exchange -> {
			try {
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
				// sendError 自身也可能抛 IOException（如连接已关闭），必须吞掉防止线程崩溃
				try {
					http.sendError(exchange, 500, "Internal Server Error");
				} catch (IOException ignored) {
				}
			} finally {
				exchange.close();
			}
		};
	}

	public long getStartTime() {
		return startTime;
	}

	public JadxGuiContext getGuiContext() {
		return guiContext;
	}

	public boolean isRunning() {
		return isRunning.get();
	}

	public ApkOverviewHandler getApkOverviewHandler() {
		return apkOverviewHandler;
	}
}
