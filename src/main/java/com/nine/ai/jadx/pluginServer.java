package com.nine.ai.jadx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.ResourceFile;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.plugins.context.GuiPluginContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * JADX插件server
 * 提供HTTP API接口用于查询类、资源和调用链信息
 */
public class pluginServer {
	private static final Logger LOG = LoggerFactory.getLogger(pluginServer.class);
	private static final String CHARSET = StandardCharsets.UTF_8.name();
	private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.$]*$");
	private static final Pattern RESOURCE_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9/_.-]+$");

	// 核心配置
	private final int port;
	private final boolean enableCors;
	private final int maxThreadPoolSize;
	private final long cacheTimeoutMs;

	// 核心组件
	private HttpServer server;
	private ExecutorService executor;
	private final JadxGuiContext guiContext;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);

	// 缓存相关
	private volatile Map<String, JavaClass> classCache;
	private volatile Map<String, ResourceFile> resourceCache;
	private final Object classCacheLock = new Object();
	private final Object resourceCacheLock = new Object();
	private volatile long lastCacheUpdate;

	/**
	 * 构造器（默认配置：端口13997、开启CORS、1线程、5分钟缓存过期）
	 */
	public pluginServer(JadxGuiContext guiContext) {
		this(guiContext, 13997, true, 1, 300000);
	}

	/**
	 * 全参数构造器（自定义配置）
	 */
	public pluginServer(JadxGuiContext guiContext, int port,
						boolean enableCors, int maxThreadPoolSize, long cacheTimeoutMs) {
		this.guiContext = Objects.requireNonNull(guiContext, "GUI context cannot be null");
		this.port = port;
		this.enableCors = enableCors;
		this.maxThreadPoolSize = Math.max(1, maxThreadPoolSize); // 至少1个线程
		this.cacheTimeoutMs = Math.max(1000, cacheTimeoutMs); // 最小1秒缓存过期
		this.lastCacheUpdate = System.currentTimeMillis();
	}

	/**
	 * 启动HTTP服务器（非阻塞，异步运行）
	 */
	public void start() {
		if (!isRunning.compareAndSet(false, true)) {
			LOG.warn("Server already running on port {}", port);
			return;
		}

		try {
			// 创建HTTP服务器，请求队列长度设为10（默认0易丢请求）
			server = HttpServer.create(new InetSocketAddress(port), 10);

			// 注册API端点（全部包装为安全处理器）
			server.createContext("/classAsk", new SafeHandler(new ClassAskHandler())); // 类代码获取接口
			server.createContext("/sourceAsk", new SafeHandler(new SourceAskHandler())); // 资源文件获取接口
			server.createContext("/resourceLineCount", new SafeHandler(new ResourceLineCountHandler())); // 文件总行数获取接口
			server.createContext("/callAsk", new SafeHandler(new CallAskHandler())); // 函数调用链获取接口
			server.createContext("/cache/clear", new SafeHandler(new ClearCacheHandler())); // 清缓存接口
			server.createContext("/status", new SafeHandler(new StatusHandler())); // 状态检查接口
			server.createContext("/health", new SafeHandler(new HealthCheckHandler())); // 健康状态检查接口
			server.createContext("/findKeywordLine", new SafeHandler(new KeywordSearchHandler())); // 搜索指定文件指定字符串出现的次数和所有行数

			// 创建命名线程池（方便调试，守护线程不阻塞Jadx退出）
			executor = Executors.newFixedThreadPool(maxThreadPoolSize, new ThreadFactory() {
				private int threadCount = 0;
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r);
					t.setName("jadx-plugin-thread-" + (++threadCount));
					t.setDaemon(true);
					t.setUncaughtExceptionHandler((thread, e) ->
							LOG.error("Uncaught exception in thread {}", thread.getName(), e));
					return t;
				}
			});

			server.setExecutor(executor);
			server.start();
			LOG.info("Plugin server started successfully | port: {} | threads: {} | CORS: {} | cache timeout: {}s",
					port, maxThreadPoolSize, enableCors, cacheTimeoutMs / 1000);
		} catch (IOException e) {
			LOG.error("Failed to start server on port {}", port, e);
			// 启动失败时清理资源
			isRunning.set(false);
			stop();
			throw new RuntimeException("Server startup failed: " + e.getMessage(), e);
		}
	}

	/**
	 * 停止服务器，保证资源释放
	 */
	public void stop() {
		if (!isRunning.compareAndSet(true, false)) {
			LOG.warn("Server is not running");
			return;
		}
		LOG.info("Shutting down plugin server on port {}...", port);
		// 停止HTTP服务器
		if (server != null) {
			try {
				server.stop(5); // 5秒优雅关闭窗口
			} catch (Exception e) {
				LOG.warn("Failed to stop HTTP server gracefully", e);
			}
			server = null;
		}
		// 关闭线程池
		if (executor != null) {
			try {
				executor.shutdown();
				if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
					LOG.warn("Thread pool not terminated in 10s, forcing shutdown");
					executor.shutdownNow();
					if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
						LOG.error("Thread pool shutdown failed");
					}
				}
			} catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
				LOG.warn("Thread pool shutdown interrupted", e);
			}
			executor = null;
		}
		// 清空缓存
		clearCache();
		LOG.info("Plugin server stopped successfully");
	}

	/**
	 * 检查服务器是否正在运行
	 */
	public boolean isRunning() {
		return isRunning.get();
	}

	/**
	 * 主动清空缓存（手动触发，如Jadx重新加载APK时）
	 */
	public void clearCache() {
		synchronized (classCacheLock) {
			classCache = null;
		}
		synchronized (resourceCacheLock) {
			resourceCache = null;
		}
		lastCacheUpdate = System.currentTimeMillis();
		LOG.info("All caches cleared manually");
	}

	/**
	 * 检查缓存是否过期（用于自动刷新）
	 */
	private boolean isCacheExpired() {
		return System.currentTimeMillis() - lastCacheUpdate > cacheTimeoutMs;
	}

	/**
	 * 获取Jadx反编译器实例（兼容不同Jadx版本）
	 */
	private JadxDecompiler getDecompiler() {
		try {
			// 校验上下文类型
			if (!(guiContext instanceof GuiPluginContext)) {
				LOG.error("Invalid GUI context type: expected GuiPluginContext, got {}",
						guiContext.getClass().getName());
				return null;
			}
			// 逐层获取反编译器（每一步都非空校验）
			GuiPluginContext ctx = (GuiPluginContext) guiContext;
			MainWindow mainWindow = ctx.getCommonContext().getMainWindow();
			if (mainWindow == null) {
				LOG.error("Jadx main window is null (not initialized?)");
				return null;
			}
			Object wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				LOG.error("Jadx project wrapper is null");
				return null;
			}
			// 反射调用getDecompiler，兼容API变化
			Method getDecompilerMethod = wrapper.getClass().getMethod("getDecompiler");
			JadxDecompiler decompiler = (JadxDecompiler) getDecompilerMethod.invoke(wrapper);
			if (decompiler == null) {
				LOG.error("JadxDecompiler instance is null");
				return null;
			}
			return decompiler;
		} catch (NoSuchMethodException e) {
			LOG.error("getDecompiler method not found (Jadx version incompatible?)", e);
			return null;
		} catch (Exception e) {
			LOG.error("Failed to retrieve JadxDecompiler instance", e);
			return null;
		}
	}

	/**
	 * 初始化/刷新类缓存（线程安全，自动过期）
	 */
	private void initClassCache(JadxDecompiler decompiler) {
		synchronized (classCacheLock) {
			// 缓存未初始化 或 已过期 → 重新加载
			if (classCache == null || isCacheExpired()) {
				Map<String, JavaClass> newCache = new HashMap<>();
				try {
					// 遍历所有类（包含内部类），多键存储提高命中率
					for (JavaClass cls : decompiler.getClassesWithInners()) {
						if (cls != null && cls.getFullName() != null) {
							newCache.put(cls.getFullName(), cls);          // 标准全限定名
							newCache.put(cls.getRawName(), cls);            // 原始名称
							// 额外添加短类名映射（用于模糊匹配）
							String shortName = cls.getName();
							if (!newCache.containsKey(shortName)) {
								newCache.put(shortName, cls);
							}
						}
					}
					// 替换缓存（volatile保证可见性）
					classCache = newCache;
					lastCacheUpdate = System.currentTimeMillis();
					LOG.info("Class cache refreshed | total classes: {}", newCache.size());
				} catch (Exception e) {
					LOG.error("Failed to initialize class cache", e);
					classCache = new HashMap<>(); // 兜底：空缓存避免NPE
				}
			}
		}
	}

	/**
	 * 初始化/刷新资源缓存（线程安全，自动过期）
	 */
	private void initResourceCache(JadxDecompiler decompiler) {
		synchronized (resourceCacheLock) {
			if (resourceCache == null || isCacheExpired()) {
				Map<String, ResourceFile> newCache = new HashMap<>();
				try {
					// 遍历所有资源，多键存储
					for (ResourceFile res : decompiler.getResources()) {
						if (res != null) {
							String originalName = res.getOriginalName();
							// Jadx API 中使用 getDeobfName() 获取处理后的标准名称，没有 getName() 方法
							String deobfName = res.getDeobfName();

							if (originalName != null) {
								newCache.put(originalName, res);
							}
							if (deobfName != null) {
								newCache.put(deobfName, res);
							}
							// 获取用于后续路径兼容处理的基准名称 (优先使用 deobfName，否则用 originalName)
							String targetName = deobfName != null ? deobfName : originalName;
							if (targetName != null) {
								// 兼容路径分隔符（Windows\ → Linux/）
								String unifiedPath = targetName.replace('\\', '/');
								if (!unifiedPath.equals(targetName)) {
									newCache.put(unifiedPath, res);
								}

								// 小写路径（某些系统区分大小写）
								String lowerCasePath = unifiedPath.toLowerCase();
								if (!lowerCasePath.equals(unifiedPath)) {
									newCache.put(lowerCasePath, res);
								}
							}
						}
					}
					resourceCache = newCache;
					lastCacheUpdate = System.currentTimeMillis();
					LOG.info("Resource cache refreshed | total resources: {}", newCache.size());
				} catch (Exception e) {
					LOG.error("Failed to initialize resource cache", e);
					resourceCache = new HashMap<>(); // 兜底
				}
			}
		}
	}

	/**
	 * 解析GET请求参数（支持URL解码，兼容特殊字符）
	 */
	private Map<String, String> parseParams(String query) {
		Map<String, String> params = new HashMap<>();
		if (query == null || query.isEmpty()) {
			return params;
		}
		try {
			// 拆分参数，处理空参数、值包含=的情况
			for (String param : query.split("&")) {
				if (param.isEmpty()) continue;
				String[] parts = param.split("=", 2);
				String key = URLDecoder.decode(parts[0], CHARSET).trim();
				String value = parts.length > 1 ? URLDecoder.decode(parts[1], CHARSET).trim() : "";
				params.put(key, value);
			}
		} catch (Exception e) {
			LOG.error("Failed to parse query parameters: {}", query, e);
		}
		return params;
	}

	/**
	 * 发送纯文本HTTP响应（统一封装，保证资源释放）
	 */
	private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
		if (exchange == null) {
			LOG.warn("sendResponse called with null HttpExchange");
			return;
		}
		// 处理OPTIONS预检请求（CORS必需）
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
			if (enableCors) {
				exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
				exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
				exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
				exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400"); // 24小时缓存预检结果
			}
			exchange.sendResponseHeaders(204, -1); // 204 No Content
			exchange.close();
			return;
		}
		// 常规响应
		byte[] responseBytes = responseText.getBytes(StandardCharsets.UTF_8);
		// 设置响应头
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.getResponseHeaders().set("Content-Length", String.valueOf(responseBytes.length));
		if (enableCors) {
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		}
		// 发送响应（保证流关闭）
		try {
			exchange.sendResponseHeaders(statusCode, responseBytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(responseBytes);
				os.flush();
			}
		} finally {
			exchange.close(); // 强制关闭，释放连接
		}
	}

	/**
	 * 安全处理器包装类：捕获所有异常，保证HttpExchange关闭
	 */
	private class SafeHandler implements HttpHandler {
		private final HttpHandler delegate;
		public SafeHandler(HttpHandler delegate) {
			this.delegate = Objects.requireNonNull(delegate);
		}
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				delegate.handle(exchange);
			} catch (Exception e) {
				String errorMsg = "Internal server error: " + e.getMessage();
				LOG.error(errorMsg, e);
				sendResponse(exchange, 500, errorMsg);
			} finally {
				// 修复：HttpExchange类没有isClosed()方法，直接调用close()
				exchange.close();
			}
		}
	}

	/**
	 * 兼容所有Jadx版本的方法签名获取工具方法
	 */
	private String getMethodSignature(JavaMethod method) {
		if (method == null) {
			return "Unknown method";
		}
		try {
			// 优先尝试高版本方法 getFullId()
			Method fullIdMethod = method.getClass().getMethod("getFullId");
			return (String) fullIdMethod.invoke(method);
		} catch (NoSuchMethodException e) {
			try {
				// 兼容中版本方法 getSignature()
				Method signatureMethod = method.getClass().getMethod("getSignature");
				return (String) signatureMethod.invoke(method);
			} catch (Exception e2) {
				try {
					// 兼容低版本方法 getDeclaredStr()
					Method declaredMethod = method.getClass().getMethod("getDeclaredStr");
					return (String) declaredMethod.invoke(method);
				} catch (Exception e3) {
					// 终极兜底：返回方法名 + 参数数量
					return method.getName() + "(" + method.getArguments().size() + " params)";
				}
			}
		} catch (Exception e) {
			// 所有反射都失败时，返回基础信息
			return method.getName() + " (unknown signature)";
		}
	}

	// ==================== 核心API处理器 ====================

	/**
	 * /classAsk - 查询类/方法代码（纯文本返回）
	 * 参数：name - 类全限定名 或 类名.方法名
	 */
	private class ClassAskHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!isRunning.get()) {
				sendResponse(exchange, 503, "Service temporarily unavailable");
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}

			Map<String, String> params = parseParams(exchange.getRequestURI().getQuery());
			String className = params.get("name");

			if (className == null || className.trim().isEmpty()) {
				sendResponse(exchange, 400, "Error: missing 'name' parameter (class name)");
				return;
			}

			JadxDecompiler decompiler = getDecompiler();
			if (decompiler == null) {
				sendResponse(exchange, 500, "Error: Jadx decompiler not available");
				return;
			}
			initClassCache(decompiler);

			String normalizedName = className.replace('$', '.').trim();
			JavaClass targetClass = null;
			String targetMethodName = null;

			synchronized (classCacheLock) {
				targetClass = classCache.get(normalizedName);

				if (targetClass == null && normalizedName.contains(".")) {
					int lastDotIdx = normalizedName.lastIndexOf('.');
					if (lastDotIdx > 0 && lastDotIdx < normalizedName.length() - 1) {
						String pureClass = normalizedName.substring(0, lastDotIdx);
						targetMethodName = normalizedName.substring(lastDotIdx + 1);
						targetClass = classCache.get(pureClass);
					}
				}

				if (targetClass == null) {
					int lastDotIdx = normalizedName.lastIndexOf('.');
					String shortName = lastDotIdx > 0 ? normalizedName.substring(lastDotIdx + 1) : normalizedName;
					targetClass = classCache.get(shortName);
					targetMethodName = null;
				}
			}

			if (targetClass == null) {
				sendResponse(exchange, 404, "Error: class not found: " + className);
				return;
			}

			try {
				// ==============================================
				// 【修复】JavaMethod 没有 getCode()，改用正确方式提取方法代码
				// ==============================================
				if (targetMethodName != null) {
					JavaMethod targetMethod = null;
					for (JavaMethod m : targetClass.getMethods()) {
						if (m != null && targetMethodName.equals(m.getName())) {
							targetMethod = m;
							break;
						}
					}

					if (targetMethod == null) {
						sendResponse(exchange, 404, "Error: method not found: " + targetMethodName);
						return;
					}

					// 【正确方法】从类代码中提取当前方法代码
					String methodCode = extractMethodCode(targetClass.getCode(), targetMethod.getName());
					sendResponse(exchange, 200, "// ==== METHOD: " + normalizedName + " ====\n\n" + methodCode);
				} else {
					// 返回类完整代码
					String classCode = targetClass.getCode();
					if (classCode == null || classCode.isEmpty()) {
						sendResponse(exchange, 500, "Error: empty class code for: " + className);
						return;
					}
					sendResponse(exchange, 200, classCode);
				}
			} catch (Exception e) {
				LOG.error("Failed to get code for: {}", className, e);
				sendResponse(exchange, 500, "Error: failed to retrieve code: " + e.getMessage());
			}
		}

		// ==============================================
		// 工具方法：从类代码中提取方法代码
		// ==============================================
		private String extractMethodCode(String classCode, String methodName) {
			if (classCode == null || methodName == null) {
				return "// Method code not available";
			}
			String[] lines = classCode.split("\n");
			StringBuilder methodCode = new StringBuilder();
			boolean inMethod = false;
			int braceCount = 0;

			for (String line : lines) {
				String trimLine = line.trim();

				// 匹配方法开始行
				if (!inMethod && trimLine.contains(" " + methodName + "(")) {
					inMethod = true;
				}

				if (inMethod) {
					methodCode.append(line).append("\n");

					// 大括号匹配
					for (char c : line.toCharArray()) {
						if (c == '{') braceCount++;
						if (c == '}') braceCount--;
					}

					// 方法结束
					if (braceCount == 0 && inMethod) {
						break;
					}
				}
			}
			return methodCode.length() > 0 ? methodCode.toString() : "// Method code not found";
		}
	}

	/**
	 * /sourceAsk - 查询资源文件内容（纯文本返回）
	 * 参数：name - 资源文件名/路径
	 */
	private class SourceAskHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!isRunning.get()) {
				sendResponse(exchange, 503, "Service temporarily unavailable");
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}

			// 解析参数
			Map<String, String> params = parseParams(exchange.getRequestURI().getQuery());
			String resName = params.get("name");

			// ========== 新增：读取 startLine 和 endLine 参数 ==========
			String startLineStr = params.get("startLine");
			String endLineStr = params.get("endLine");

			// 参数校验
			if (resName == null || resName.trim().isEmpty()) {
				sendResponse(exchange, 400, "Error: missing 'name' parameter (resource name)");
				return;
			}

			// 资源路径格式校验（防止路径遍历攻击）
			if (!RESOURCE_PATH_PATTERN.matcher(resName).matches()) {
				sendResponse(exchange, 400, "Error: invalid resource path: " + resName);
				return;
			}

			// 获取反编译器
			JadxDecompiler decompiler = getDecompiler();
			if (decompiler == null) {
				sendResponse(exchange, 500, "Error: Jadx decompiler not available");
				return;
			}

			// 初始化缓存
			initResourceCache(decompiler);

			// 查找资源
			ResourceFile targetRes = null;
			synchronized (resourceCacheLock) {
				targetRes = resourceCache.get(resName);
				// 兜底：尝试统一路径分隔符匹配
				if (targetRes == null) {
					String unifiedResName = resName.replace('\\', '/');
					targetRes = resourceCache.get(unifiedResName);
				}
				// 再兜底：尝试小写路径匹配
				if (targetRes == null) {
					String lowerCaseResName = resName.toLowerCase();
					targetRes = resourceCache.get(lowerCaseResName);
				}
			}

			// 资源不存在
			if (targetRes == null) {
				sendResponse(exchange, 404, "Error: resource not found: " + resName);
				return;
			}

			// 加载资源内容
			try {
				String resContent = getResourceContent(targetRes);
				if (resContent == null || resContent.isEmpty()) {
					sendResponse(exchange, 500, "Error: empty text content for resource: " + resName);
					return;
				}

				// ========== 核心：截取指定行范围 ==========
				String finalContent = extractLineRange(resContent, startLineStr, endLineStr);
				sendResponse(exchange, 200, finalContent);

			} catch (Exception e) {
				LOG.error("Failed to load resource: {}", resName, e);
				sendResponse(exchange, 500, "Error: failed to load resource: " + e.getMessage());
			}
		}

		// =======================================================================
		// 工具方法：从全文本中截取 [startLine, endLine] 范围内容
		// 不传参数 → 返回全部
		// startLine 不传 → 默认从第1行开始
		// endLine 不传 → 默认读到最后一行
		// =======================================================================
		private String extractLineRange(String fullContent, String startLineStr, String endLineStr) {
			if (fullContent == null || fullContent.isEmpty()) {
				return fullContent;
			}

			// 按行拆分
			String[] lines = fullContent.split("\\R"); // 兼容所有换行符
			int totalLines = lines.length;

			// 解析 startLine，默认 1
			int startLine = 1;
			try {
				if (startLineStr != null && !startLineStr.trim().isEmpty()) {
					startLine = Integer.parseInt(startLineStr.trim());
				}
			} catch (NumberFormatException e) {
				startLine = 1;
			}

			// 解析 endLine，默认最后一行
			int endLine = totalLines;
			try {
				if (endLineStr != null && !endLineStr.trim().isEmpty()) {
					endLine = Integer.parseInt(endLineStr.trim());
				}
			} catch (NumberFormatException e) {
				endLine = totalLines;
			}

			// 安全边界修正
			startLine = Math.max(startLine, 1);
			endLine = Math.min(endLine, totalLines);
			if (startLine > endLine) {
				int temp = startLine;
				startLine = endLine;
				endLine = temp;
			}

			// 拼接结果
			StringBuilder sb = new StringBuilder();
			sb.append("// ==== Resource lines ").append(startLine).append(" - ").append(endLine)
					.append(" (Total lines: ").append(totalLines).append(") ====\n\n");

			for (int i = startLine - 1; i <= endLine - 1; i++) {
				sb.append(lines[i]).append("\n");
			}

			return sb.toString();
		}
	}

	// =======================================================================
	// 获取指定文件总行数
	// =======================================================================
	private class ResourceLineCountHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!isRunning.get()) {
				sendResponse(exchange, 503, "Service temporarily unavailable");
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}

			// 解析参数
			Map<String, String> params = parseParams(exchange.getRequestURI().getQuery());
			String resName = params.get("name");

			// 参数校验
			if (resName == null || resName.trim().isEmpty()) {
				sendResponse(exchange, 400, "Error: missing 'name' parameter (resource name)");
				return;
			}

			// 资源路径安全校验
			if (!RESOURCE_PATH_PATTERN.matcher(resName).matches()) {
				sendResponse(exchange, 400, "Error: invalid resource path: " + resName);
				return;
			}

			// 获取反编译器
			JadxDecompiler decompiler = getDecompiler();
			if (decompiler == null) {
				sendResponse(exchange, 500, "Error: Jadx decompiler not available");
				return;
			}
			initResourceCache(decompiler);

			// 查找资源（完全沿用你原来的兼容逻辑）
			ResourceFile targetRes = null;
			synchronized (resourceCacheLock) {
				targetRes = resourceCache.get(resName);
				if (targetRes == null) {
					String unified = resName.replace('\\', '/');
					targetRes = resourceCache.get(unified);
				}
				if (targetRes == null) {
					targetRes = resourceCache.get(resName.toLowerCase());
				}
			}

			if (targetRes == null) {
				sendResponse(exchange, 404, "Error: resource not found: " + resName);
				return;
			}

			// 获取内容 → 计算行数
			try {
				String content = getResourceContent(targetRes);
				int lineCount = 0;
				if (content != null && !content.isEmpty()) {
					lineCount = content.split("\\R").length; // 兼容所有换行符
				}

				// ==============================
				// 返回：纯数字总行数
				// ==============================
				sendResponse(exchange, 200, String.valueOf(lineCount));

			} catch (Exception e) {
				LOG.error("Failed to get resource line count: {}", resName, e);
				sendResponse(exchange, 500, "Error: failed to get line count: " + e.getMessage());
			}
		}
	}

	/**
	 * 兼容所有Jadx版本的资源内容获取工具方法
	 */
	private String getResourceContent(ResourceFile res) {
		if (res == null) {
			return null;
		}
		try {
			// 方案1：高版本 - ResourceFileContent（优先）
			Method loadContentMethod = res.getClass().getMethod("loadContent");
			Object contentObj = loadContentMethod.invoke(res);
			if (contentObj == null) {
				return null;
			}
			// 尝试获取文本内容（多版本兼容）
			try {
				// 高版本：content.getText().getCodeStr()
				Method getTextMethod = contentObj.getClass().getMethod("getText");
				Object textObj = getTextMethod.invoke(contentObj);
				if (textObj != null) {
					Method getCodeStrMethod = textObj.getClass().getMethod("getCodeStr");
					return (String) getCodeStrMethod.invoke(textObj);
				}
			} catch (NoSuchMethodException e1) {
				try {
					// 中版本：content.getCodeStr()
					Method getCodeStrMethod = contentObj.getClass().getMethod("getCodeStr");
					return (String) getCodeStrMethod.invoke(contentObj);
				} catch (NoSuchMethodException e2) {
					try {
						// 低版本：content.getContent()
						Method getContentMethod = contentObj.getClass().getMethod("getContent");
						Object content = getContentMethod.invoke(contentObj);
						return content != null ? content.toString() : null;
					} catch (Exception e3) {
						// 终极兜底：直接toString()
						return contentObj.toString();
					}
				}
			}
			// 补充：如果所有文本获取尝试都失败（如textObj为null），返回兜底值
			return contentObj.toString();
		} catch (Exception e) {
			LOG.error("Failed to load resource content", e);
			return null;
		}
	}

	/**
	 * /callAsk - 查询方法调用链（纯文本返回）
	 * 参数：name - 类名.方法名
	 */
	private class CallAskHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!isRunning.get()) {
				sendResponse(exchange, 503, "Service temporarily unavailable");
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}

			// 解析参数
			Map<String, String> params = parseParams(exchange.getRequestURI().getQuery());
			String fullName = params.get("name");

			// 参数校验
			if (fullName == null || !fullName.contains(".")) {
				sendResponse(exchange, 400, "Error: 'name' must be a valid class or method full name");
				return;
			}

			JadxDecompiler decompiler = getDecompiler();
			if (decompiler == null) {
				sendResponse(exchange, 500, "Error: Jadx decompiler not available");
				return;
			}
			initClassCache(decompiler);

			String normalizedName = fullName.replace('$', '.').trim();
			List<JavaNode> callers = null;
			String targetDisplay = "";

			try {
				// ==============================================
				// 【核心修改】自动判断：是类？还是方法？
				// ==============================================
				JavaClass targetClass = findClass(normalizedName);
				if (targetClass != null) {
					// ======================
					// 情况1：传入的是 类
					// ======================
					targetDisplay = "Class: " + normalizedName;
					callers = targetClass.getUseIn(); // 获取整个类的调用链
				} else {
					// ======================
					// 情况2：传入的是 方法
					// ======================
					int lastDotIdx = normalizedName.lastIndexOf('.');
					String className = normalizedName.substring(0, lastDotIdx);
					String methodName = normalizedName.substring(lastDotIdx + 1);

					targetClass = findClass(className);
					if (targetClass == null) {
						sendResponse(exchange, 404, "Error: class not found: " + className);
						return;
					}

					JavaMethod targetMethod = findMethod(targetClass, methodName);
					if (targetMethod == null) {
						sendResponse(exchange, 404, "Error: method not found: " + methodName);
						return;
					}

					targetDisplay = "Method: " + fullName;
					callers = targetMethod.getUseIn();
				}

				// 构建响应
				StringBuilder response = new StringBuilder();
				response.append("// ====== Call Chain for: ").append(targetDisplay).append(" ======\n");
				response.append("// Callers Count: ").append(callers != null ? callers.size() : 0).append("\n\n");

				if (callers == null || callers.isEmpty()) {
					response.append("// No callers found\n");
				} else {
					for (int i = 0; i < callers.size(); i++) {
						JavaNode caller = callers.get(i);
						if (caller != null) {
							response.append(String.format("%d. %s (Type: %s)\n",
									i + 1,
									caller.getFullName(),
									caller.getClass().getSimpleName()));
						}
					}
				}

				sendResponse(exchange, 200, response.toString());

			} catch (Exception e) {
				sendResponse(exchange, 500, "Error: " + e.getMessage());
			}
		}
	}

		// ======================
		// 工具方法：查找类
		// ======================
		private JavaClass findClass(String className) {
			synchronized (classCacheLock) {
				return classCache.get(className);
			}
		}

		// ======================
		// 工具方法：查找方法
		// ======================
		private JavaMethod findMethod(JavaClass clazz, String methodName) {
			for (JavaMethod m : clazz.getMethods()) {
				if (m != null && methodName.equals(m.getName())) {
					return m;
				}
			}
			return null;
		}

	/**
	 * /cache/clear - 清空缓存（POST请求）
	 */
	private class ClearCacheHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only POST)");
				return;
			}
			clearCache();
			sendResponse(exchange, 200, "Success: all caches cleared");
		}
	}

	/**
	 * /status - 服务器状态监控（GET请求）
	 */
	private class StatusHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}
			// 构建状态信息（纯文本，易读）
			StringBuilder status = new StringBuilder();
			status.append("===== JADX Plugin Server Status =====\n");
			status.append("Server Port: ").append(port).append("\n");
			status.append("Running: ").append(isRunning.get()).append("\n");
			status.append("CORS Enabled: ").append(enableCors).append("\n");
			status.append("Thread Pool Size: ").append(maxThreadPoolSize).append("\n");
			status.append("Cache Timeout: ").append(cacheTimeoutMs / 1000).append(" seconds\n");
			// 缓存信息
			synchronized (classCacheLock) {
				status.append("Class Cache Size: ").append(classCache != null ? classCache.size() : 0).append("\n");
			}
			synchronized (resourceCacheLock) {
				status.append("Resource Cache Size: ").append(resourceCache != null ? resourceCache.size() : 0).append("\n");
			}
			status.append("Cache Last Updated: ").append(new Date(lastCacheUpdate)).append("\n");
			status.append("Cache Expired: ").append(isCacheExpired()).append("\n");

			sendResponse(exchange, 200, status.toString());
		}
	}

	/**
	 * /health - 健康检查（轻量级状态检查）
	 */
	private class HealthCheckHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}
			String healthStatus = isRunning.get() ? "healthy" : "unhealthy";
			sendResponse(exchange, 200, healthStatus);
		}
	}

	/**
	 * /findKeywordLine -获取关键字符串所在的所有行数和出现次数
	 */
	private class KeywordSearchHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (!isRunning.get()) {
				sendResponse(exchange, 503, "Service temporarily unavailable");
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				sendResponse(exchange, 405, "Method not allowed (only GET)");
				return;
			}

			// 解析参数
			Map<String, String> params = parseParams(exchange.getRequestURI().getQuery());
			String resName = params.get("name");
			String keyword = params.get("keyword");

			// 参数校验
			if (resName == null || resName.trim().isEmpty()) {
				sendResponse(exchange, 400, "Error: missing 'name' parameter");
				return;
			}
			if (keyword == null || keyword.trim().isEmpty()) {
				sendResponse(exchange, 400, "Error: missing 'keyword' parameter");
				return;
			}

			// 路径安全校验
			if (!RESOURCE_PATH_PATTERN.matcher(resName).matches()) {
				sendResponse(exchange, 400, "Error: invalid resource path");
				return;
			}

			// 获取反编译器
			JadxDecompiler decompiler = getDecompiler();
			if (decompiler == null) {
				sendResponse(exchange, 500, "Error: Jadx decompiler not available");
				return;
			}
			initResourceCache(decompiler);

			// 查找资源（沿用你原有兼容逻辑）
			ResourceFile targetRes = null;
			synchronized (resourceCacheLock) {
				targetRes = resourceCache.get(resName);
				if (targetRes == null) {
					String unified = resName.replace('\\', '/');
					targetRes = resourceCache.get(unified);
				}
				if (targetRes == null) {
					targetRes = resourceCache.get(resName.toLowerCase());
				}
			}

			if (targetRes == null) {
				sendResponse(exchange, 404, "Error: resource not found: " + resName);
				return;
			}

			try {
				// 获取文件内容
				String content = getResourceContent(targetRes);
				if (content == null || content.isEmpty()) {
					sendResponse(exchange, 200, "{\"lines\":[]}");
					return;
				}

				// 搜索关键字，返回所有行号
				List<Integer> lineNumbers = new ArrayList<>();
				String[] lines = content.split("\\R");
				String lowerKeyword = keyword.toLowerCase();

				for (int i = 0; i < lines.length; i++) {
					String line = lines[i].toLowerCase();
					if (line.contains(lowerKeyword)) {
						lineNumbers.add(i + 1); // 行号从 1 开始
					}
				}

				// 构建 JSON 返回
				Map<String, Object> result = new HashMap<>();
				result.put("file", resName);
				result.put("keyword", keyword);
				result.put("lines", lineNumbers);
				result.put("count", lineNumbers.size());

				// 转 JSON（你可以用 GSON，这里手动拼接保证兼容）
				String json = "{"
						+ "\"file\":\"" + resName + "\","
						+ "\"keyword\":\"" + keyword + "\","
						+ "\"count\":" + lineNumbers.size() + ","
						+ "\"lines\":" + lineNumbers.toString()
						+ "}";

				sendResponse(exchange, 200, json);

			} catch (Exception e) {
				LOG.error("Keyword search failed for: {} in {}", keyword, resName, e);
				sendResponse(exchange, 500, "Error: search failed: " + e.getMessage());
			}
		}
	}
}
