package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ClassSearchHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(ClassSearchHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	private static final int MAX_CACHE_ENTRIES = 100;
	/** LRU cache for code-search results: "term|package" -> list of matching class names */
	private static final Map<String, List<String>> CODE_SEARCH_CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
					return size() > MAX_CACHE_ENTRIES;
				}
			});

	public static void clearSearchCache() {
		CODE_SEARCH_CACHE.clear();
	}

	private enum SearchLocation {
		CLASS_NAME, CODE
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			http.sendError(exchange, 405, "Only GET allowed");
			return;
		}

		Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
		String searchTerm = params.get("class_name");
		String packageFilter = params.get("package");
		String searchIn = params.get("search_in");

		if (searchTerm == null || searchTerm.isBlank()) {
			http.sendError(exchange, 400, "Missing required parameter: class_name");
			return;
		}

		try {
			Set<SearchLocation> locations = parseSearchLocations(searchIn);

			if (locations.contains(SearchLocation.CODE)) {
				handleCodeSearchAsync(exchange, searchTerm, packageFilter, locations, params);
			} else {
				handleClassNameSearchSync(exchange, searchTerm, packageFilter, params);
			}
		} catch (Exception e) {
			http.sendError(exchange, 500, "Search error: " + e.getMessage());
		}
	}

	// ====================== Sync path: class_name only ======================

	private void handleClassNameSearchSync(
			HttpExchange exchange, String searchTerm, String packageFilter,
			Map<String, String> params
	) throws IOException {
		JadxDecompiler decompiler = JadxUtil.getDecompiler();
		if (decompiler == null) {
			http.sendError(exchange, 500, "Decompiler not available");
			return;
		}

		String lowerTerm = searchTerm.toLowerCase();
		List<JavaClass> allClasses = decompiler.getClassesWithInners();
		boolean applyPackageFilter = isValidPackageFilter(packageFilter);

		List<JavaClass> matches = new ArrayList<>();
		for (JavaClass cls : allClasses) {
			try {
				if (applyPackageFilter && packageFilter != null && !cls.getFullName().startsWith(packageFilter)) {
					continue;
				}
				if (cls.getFullName().toLowerCase().contains(lowerTerm)) {
					matches.add(cls);
				}
			} catch (Exception ignored) {}
		}

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

		Map<String, Object> result = PageUtil.paginate(
				matches, offset, limit, "class-list", "classes", JavaClass::getFullName
		);

		http.sendResponse(exchange, 200, http.toJson(result));
	}

	// ====================== Async path: search_in contains code ======================

	private void handleCodeSearchAsync(
			HttpExchange exchange, String searchTerm, String packageFilter,
			Set<SearchLocation> locations, Map<String, String> params
	) throws IOException {
		String cacheKey = buildCacheKey(searchTerm, packageFilter);

		// Check cache first
		List<String> cached = CODE_SEARCH_CACHE.get(cacheKey);
		if (cached != null) {
			logger.info("Code search cache hit for: {}", searchTerm);
			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);
			Map<String, Object> paginated = PageUtil.paginate(
					cached, offset, limit, "class-list", "classes", item -> item
			);

			String taskId = TaskManager.createHighLoadTask("CLASS_CODE_SEARCH");
			TaskManager.updateTask(taskId, "SUCCESS", http.toJson(paginated));

			String response = String.format(
					"{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Result from cache\"}",
					taskId
			);
			http.sendResponse(exchange, 202, response);
			return;
		}

		// Cache miss — run async
		String taskId = TaskManager.createHighLoadTask("CLASS_CODE_SEARCH");
		logger.info("Started background class code search task: {} for term: {}", taskId, searchTerm);

		int offset = HttpUtil.parseInt(params.get("offset"), 0);
		int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

		CompletableFuture.runAsync(() -> {
			try {
				JadxDecompiler decompiler = JadxUtil.getDecompiler();
				if (decompiler == null) {
					TaskManager.updateTask(taskId, "FAILED", "Decompiler not available");
					return;
				}

				String lowerTerm = searchTerm.toLowerCase();
				boolean applyPackageFilter = isValidPackageFilter(packageFilter);
				boolean searchName = locations.contains(SearchLocation.CLASS_NAME);

				// 使用全局代码索引，避免重复反编译
				Map<String, String> codeIndex = CodeIndexManager.getInstance().getIndex(decompiler);

				List<String> matchNames = new ArrayList<>();
				for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
					try {
						String className = entry.getKey();
						if (applyPackageFilter && packageFilter != null
								&& !className.startsWith(packageFilter)) {
							continue;
						}
						if (searchName && className.toLowerCase().contains(lowerTerm)) {
							matchNames.add(className);
							continue;
						}
						if (entry.getValue().toLowerCase().contains(lowerTerm)) {
							matchNames.add(className);
						}
					} catch (Exception ignored) {}
				}

				CODE_SEARCH_CACHE.put(cacheKey, matchNames);

				// Paginate and store result
				Map<String, Object> paginated = PageUtil.paginate(
						matchNames, offset, limit, "class-list", "classes", item -> item
				);
				TaskManager.updateTask(taskId, "SUCCESS", http.toJson(paginated));

			} catch (Exception e) {
				logger.error("Async class code search failed", e);
				TaskManager.updateTask(taskId, "FAILED", e.getMessage());
			}
		}, PluginServer.getAsyncPool());

		String response = String.format(
				"{\"status\":\"ACCEPTED\", \"task_id\":\"%s\", \"message\":\"Code search started\"}",
				taskId
		);
		http.sendResponse(exchange, 202, response);
	}

	// ====================== Helpers ======================

	private String buildCacheKey(String term, String pkg) {
		return (term != null ? term.toLowerCase() : "") + "|" + (pkg != null ? pkg : "");
	}

	private Set<SearchLocation> parseSearchLocations(String searchIn) {
		Set<SearchLocation> set = new HashSet<>();
		if (searchIn == null || searchIn.isBlank()) {
			set.add(SearchLocation.CLASS_NAME);
			return set;
		}
		String[] parts = searchIn.toLowerCase().split(",");
		for (String p : parts) {
			if (p.contains("class_name")) set.add(SearchLocation.CLASS_NAME);
			if (p.contains("code")) set.add(SearchLocation.CODE);
		}
		if (set.isEmpty()) set.add(SearchLocation.CLASS_NAME);
		return set;
	}

	private boolean isValidPackageFilter(String pkg) {
		if (pkg == null) return false;
		return !pkg.matches("^p[0-9]{3}$");
	}
}
