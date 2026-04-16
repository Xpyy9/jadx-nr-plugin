package com.nine.ai.jadx.server.handler.search;

import com.nine.ai.jadx.util.HttpUtil;
import com.nine.ai.jadx.util.JadxUtil;
import com.nine.ai.jadx.util.PageUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.IOException;
import java.util.*;

public class ClassSearchHandler implements HttpHandler {
	private final HttpUtil http = HttpUtil.getInstance();

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
			JadxDecompiler decompiler = JadxUtil.getDecompiler();
			if (decompiler == null) {
				http.sendError(exchange, 500, "Decompiler not available");
				return;
			}

			String lowerTerm = searchTerm.toLowerCase();
			List<JavaClass> allClasses = decompiler.getClassesWithInners();

			Set<SearchLocation> locations = parseSearchLocations(searchIn);
			boolean applyPackageFilter = isValidPackageFilter(packageFilter);

			List<JavaClass> matches = searchOptimized(allClasses, lowerTerm, locations, packageFilter, applyPackageFilter);

			int offset = HttpUtil.parseInt(params.get("offset"), 0);
			int limit = HttpUtil.parseInt(params.get("limit"), PageUtil.DEFAULT_PAGE_SIZE);

			Map<String, Object> result = PageUtil.paginate(
					matches, offset, limit, "class-list", "classes", JavaClass::getFullName
			);

			http.sendResponse(exchange, 200, http.toJson(result));

		} catch (Exception e) {
			http.sendError(exchange, 500, "Search error: " + e.getMessage());
		}
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

	private List<JavaClass> searchOptimized(
			List<JavaClass> allClasses,
			String term,
			Set<SearchLocation> locations,
			String packageFilter,
			boolean applyPackageFilter
	) {
		List<JavaClass> matches = new ArrayList<>();
		boolean searchName = locations.contains(SearchLocation.CLASS_NAME);
		boolean searchCode = locations.contains(SearchLocation.CODE);

		for (JavaClass cls : allClasses) {
			try {
				if (applyPackageFilter && packageFilter != null && !cls.getFullName().startsWith(packageFilter)) {
					continue;
				}
				if (searchName && cls.getFullName().toLowerCase().contains(term)) {
					matches.add(cls);
					continue;
				}
				if (searchCode) {
					String code = cls.getCode();
					if (code != null && code.toLowerCase().contains(term)) {
						matches.add(cls);
					}
				}
			} catch (Exception ignored) {}
		}
		return matches;
	}

	private boolean isValidPackageFilter(String pkg) {
		if (pkg == null) return false;
		return !pkg.matches("^p[0-9]{3}$");
	}
}
