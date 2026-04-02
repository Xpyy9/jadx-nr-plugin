package com.nine.ai.jadx.server;

import com.nine.ai.jadx.server.handler.rename.*;
import com.nine.ai.jadx.server.handler.basic.*;
import com.nine.ai.jadx.server.handler.code.*;
import com.nine.ai.jadx.server.handler.resource.*;
import com.nine.ai.jadx.server.handler.search.*;
import com.nine.ai.jadx.server.handler.xrefs.XrefsHandler;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件HTTP服务器核心类
 * 负责：生命周期管理、路由注册、全局 CORS 注入、线程调度
 */
public class PluginServer {
	private static final Logger LOG = LoggerFactory.getLogger(PluginServer.class);

	private static PluginServer instance;
	private final JadxGuiContext guiContext;
	private final MainWindow mainWindow;

	// 配置信息
	private final int port = 13997;
	private final boolean enableCors = true;
	private final int threadPoolSize = 5;

	private HttpServer server;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private long startTime = 0;

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
			server = HttpServer.create(new InetSocketAddress(port), 10);

			server.setExecutor(Executors.newFixedThreadPool(threadPoolSize, new ThreadFactory() {
				private int count = 0;
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r);
					t.setName("jadx-agent-http-" + (++count));
					t.setDaemon(true);
					return t;
				}
			}));

			// ====================== 路由注册中心 ======================
			// 1. 源码探索 (Code Insight)
			server.createContext("/getClassCode", wrap(new ClassHandler()));
			server.createContext("/getAllClasses", wrap(new AllClassHandler()));
			server.createContext("/getClassStructure", wrap(new ClassStructureHandler()));
			server.createContext("/getClassSmali", wrap(new SmaliHandler()));

			// 2. 资源解析 (Resources)
			server.createContext("/getAllResourceNames", wrap(new AllResourceFileNameHandler()));
			server.createContext("/getResourceFile", wrap(new SourceHandler()));
			server.createContext("/getMainAppClasses", wrap(new MainApplicationHandler()));
			server.createContext("/getMainActivity", wrap(new MainActivityHandler()));

			// 3. 搜索与侦察 (Search & Recon)
			server.createContext("/searchMethod", wrap(new MethodSearchHandler()));
			server.createContext("/searchClass", wrap(new ClassSearchHandler()));
			server.createContext("/searchString", wrap(new StringSearchHandler()));
			server.createContext("/getXrefs", wrap(new XrefsHandler()));
			server.createContext("/scanCrypto", wrap(new CryptoScanHandler()));

			// 4. 重构与去混淆 (Refactor)
			server.createContext("/classRename", wrap(new ClassRenameHandler(mainWindow)));
			server.createContext("/methodRename", wrap(new MethodRenameHandler(mainWindow)));
			server.createContext("/fieldRename", wrap(new FieldRenameHandler(mainWindow)));
			server.createContext("/variableRename", wrap(new VariableRenameHandler(mainWindow)));

			// 5. 系统与运维 (Basic)
			server.createContext("/cacheClear", wrap(new ClearCacheHandler()));
			server.createContext("/systemStatus", wrap(new SystemStatusHandler()));
			server.createContext("/refactorMapping", wrap(new MappingExportHandler()));
			server.createContext("/taskStatus", wrap(new TaskStatusHandler()));

			server.start();
			this.startTime = System.currentTimeMillis();
			LOG.info("JADX Agent Server started on port {}", port);

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
		if (server != null) {
			server.stop(2);
			server = null;
		}
		this.startTime = 0;
	}

	// ====================== 工具方法 ======================

	/**
	 * 高级异常包装器：
	 * 1. 统一处理跨域 (CORS)
	 * 2. 统一处理 OPTIONS 预检请求
	 * 3. 捕获未处理异常，防止线程崩溃
	 */
	private HttpHandler wrap(HttpHandler handler) {
		return exchange -> {
			try {
				if (enableCors) {
					exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
					exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
					exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");

					if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
						exchange.sendResponseHeaders(204, -1);
						return;
					}
				}
				handler.handle(exchange);
			} catch (Exception e) {
				LOG.error("Handler error: {}", exchange.getRequestURI(), e);
				try {
					byte[] resp = "{\"error\":\"Internal Server Error\"}".getBytes();
					exchange.sendResponseHeaders(500, resp.length);
					exchange.getResponseBody().write(resp);
				} catch (IOException ignored) {}
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

	public boolean isCorsEnabled() {
		return enableCors;
	}
}
