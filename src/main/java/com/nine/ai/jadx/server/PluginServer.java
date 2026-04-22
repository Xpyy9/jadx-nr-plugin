package com.nine.ai.jadx.server;

import com.nine.ai.jadx.server.handler.rename.*;
import com.nine.ai.jadx.server.handler.basic.*;
import com.nine.ai.jadx.server.handler.code.*;
import com.nine.ai.jadx.server.handler.resource.*;
import com.nine.ai.jadx.server.handler.search.*;
import com.nine.ai.jadx.server.handler.xrefs.XrefsHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
			route("/resourceExplorer", new ResourceExplorerHandler());
			route("/searchEngine", new SearchEngineHandler());
			route("/getXrefs", new XrefsHandler());
			route("/refactor", new RefactorHandler(mainWindow));

			SystemManagerHandler systemManagerHandler = new SystemManagerHandler();
			this.apkOverviewHandler = systemManagerHandler.getApkOverviewHandler();
			route("/systemManager", systemManagerHandler);

			server.start();
			this.startTime = System.currentTimeMillis();
			LOG.info("JADX Agent Server started on port {}", PORT);

			// Pre-build the APK overview cache in a background thread
			// so the server is responsive immediately while data loads
			Thread preloadThread = new Thread(() -> {
				try {
					apkOverviewHandler.preload();
				} catch (Exception e) {
					LOG.warn("APK overview preload failed, will rebuild on first request", e);
				}
			}, "jadx-apk-overview-preload");
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

	/**
	 * 高级异常包装器：
	 * 1. 统一处理跨域 (CORS)
	 * 2. 统一处理 OPTIONS 预检请求
	 * 3. 捕获未处理异常，防止线程崩溃
	 */
	private HttpHandler wrap(HttpHandler handler) {
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
				sendError(exchange, 500, "{\"error\":\"Internal Server Error\",\"source\":\"jadx-plugin\"}");
			} finally {
				exchange.close();
			}
		};
	}

	private static void sendError(HttpExchange exchange, int code, String body) {
		try {
			byte[] resp = body.getBytes();
			exchange.sendResponseHeaders(code, resp.length);
			exchange.getResponseBody().write(resp);
		} catch (IOException ignored) {
		}
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
