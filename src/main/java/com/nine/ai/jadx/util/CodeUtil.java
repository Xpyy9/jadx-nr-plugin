package com.nine.ai.jadx.util;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import java.util.*;

public class CodeUtil {
	private static Map<String, JavaClass> classCache = null;
	// 重命名映射表
	private static final Map<String, String> renameMapping = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 初始化或获取全局类缓存。
	 * 解决了每次 HTTP 请求都重新遍历几万个类的巨大性能损耗。
	 */
	public static synchronized Map<String, JavaClass> initClassCache(JadxDecompiler decompiler) {
		if (classCache != null && !classCache.isEmpty()) {
			return classCache;
		}

		classCache = new HashMap<>();
		if (decompiler == null) return classCache;

		for (JavaClass cls : decompiler.getClassesWithInners()) {
			if (cls == null || cls.getFullName() == null) continue;
			classCache.put(cls.getFullName(), cls);
			classCache.put(cls.getRawName(), cls);
		}
		return classCache;
	}

	public static JavaClass findClass(Map<String, JavaClass> cache, String name) {
		if (cache == null || name == null) return null;

		// 如果传入的是包含签名的函数名 (例如: com.app.Utils.encrypt(Ljava/lang/String;)V )
		int parenIndex = name.indexOf('(');
		String cleanName = parenIndex > 0 ? name.substring(0, parenIndex) : name;

		String norm = cleanName.replace('$', '.').trim();

		JavaClass cls = cache.get(norm);
		if (cls != null) return cls;

		if (norm.contains(".")) {
			int dot = norm.lastIndexOf('.');
			String clsPart = norm.substring(0, dot);
			cls = cache.get(clsPart);
			if (cls != null) return cls;
		}

		int dot = norm.lastIndexOf('.');
		String shortName = dot > 0 ? norm.substring(dot + 1) : norm;
		for (JavaClass c : cache.values()) {
			String full = c.getFullName();
			int d = full.lastIndexOf('.');
			String sn = d > 0 ? full.substring(d + 1) : full;
			if (shortName.equals(sn)) return c;
		}
		return null;
	}

	public static boolean isMethodName(String s) {
		if (s == null) return false;
		int parenIndex = s.indexOf('(');
		String checkStr = parenIndex > 0 ? s.substring(0, parenIndex) : s;
		return checkStr.contains(".") && checkStr.lastIndexOf('.') < checkStr.length() - 1;
	}

	public static String extractMethodName(String s) {
		if (!isMethodName(s)) return null;
		int parenIndex = s.indexOf('(');
		String checkStr = parenIndex > 0 ? s.substring(0, parenIndex) : s;
		return checkStr.substring(checkStr.lastIndexOf('.') + 1);
	}

	public static String extractClassName(String s) {
		if (!isMethodName(s)) return s;
		int parenIndex = s.indexOf('(');
		String checkStr = parenIndex > 0 ? s.substring(0, parenIndex) : s;
		// 截取最后一个 '.' 之前的部分作为类名
		return checkStr.substring(0, checkStr.lastIndexOf('.'));
	}

	public static String extractLineRange(String content, int start, int end) {
		if (content == null) return "";
		String[] lines = content.split("\\R");
		int total = lines.length;
		start = Math.max(start, 1);
		end = Math.min(end, total);
		StringBuilder sb = new StringBuilder();
		sb.append("// Lines ").append(start).append("-").append(end).append("/").append(total).append("\n\n");
		for (int i = start - 1; i <= end - 1; i++) {
			sb.append(lines[i]).append("\n");
		}
		return sb.toString();
	}

	public static synchronized void clearClassCache() {
		if (classCache != null) {
			classCache.clear();
			classCache = null;
		}
	}

	// 重命名映射处理
	public static void recordRename(String newName, String oldName) {
		renameMapping.put(newName, oldName);
	}

	public static Map<String, String> getRenameMapping() {
		return renameMapping;
	}
}
