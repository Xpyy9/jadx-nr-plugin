package com.nine.ai.jadx.util;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CodeUtil {
	private static final Logger logger = LoggerFactory.getLogger(CodeUtil.class);
	private static Map<String, JavaClass> classCache = null;
	/** 短名反向索引：simpleName → 全限定类名列表（O(1) 短名查找） */
	private static Map<String, List<JavaClass>> shortNameIndex = null;
	// 重命名映射表
	private static final Map<String, String> renameMapping = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 初始化或获取全局类缓存。
	 * 同时构建短名反向索引，供 findClass 短名查找 O(1) 使用。
	 */
	public static synchronized Map<String, JavaClass> initClassCache(JadxDecompiler decompiler) {
		if (classCache != null && !classCache.isEmpty()) {
			return classCache;
		}

		classCache = new HashMap<>();
		shortNameIndex = new HashMap<>();
		if (decompiler == null) return classCache;

		for (JavaClass cls : decompiler.getClassesWithInners()) {
			if (cls == null || cls.getFullName() == null) continue;
			classCache.put(cls.getFullName(), cls);
			classCache.put(cls.getRawName(), cls);

			// 构建短名索引
			String fullName = cls.getFullName();
			int dot = fullName.lastIndexOf('.');
			String shortName = dot > 0 ? fullName.substring(dot + 1) : fullName;
			shortNameIndex.computeIfAbsent(shortName, k -> new ArrayList<>()).add(cls);
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

		// 使用短名反向索引做 O(1) 查找
		int dot = norm.lastIndexOf('.');
		String shortName = dot > 0 ? norm.substring(dot + 1) : norm;
		if (shortNameIndex != null) {
			List<JavaClass> candidates = shortNameIndex.get(shortName);
			if (candidates != null && !candidates.isEmpty()) {
				return candidates.get(0);
			}
		}

		// 重命名回退：传入的可能是重命名后的类名，解析回原始名称重试
		String originalName = resolveOriginalName(norm);
		if (originalName != null) {
			cls = cache.get(originalName);
			if (cls != null) return cls;
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
		if (shortNameIndex != null) {
			shortNameIndex.clear();
			shortNameIndex = null;
		}
	}

	// 重命名映射处理
	public static void recordRename(String newName, String oldName) {
		renameMapping.put(newName, oldName);
	}

	/**
	 * 反向解析重命名：如果传入的是新名称，返回原始名称；否则返回 null。
	 */
	public static String resolveOriginalName(String possiblyRenamed) {
		if (possiblyRenamed == null) return null;
		return renameMapping.get(possiblyRenamed);
	}

	public static Map<String, String> getRenameMapping() {
		return renameMapping;
	}

	// ==================== 公共查找方法 ====================

	/**
	 * 深度查找类：cache → rename fallback → 内部类 $ 替换 → decompiler 遍历兜底
	 */
	public static JavaClass findClassDeeply(Map<String, JavaClass> cache, String name, JadxDecompiler decompiler) {
		JavaClass cls = findClass(cache, name);
		if (cls != null) return cls;

		String originalName = resolveOriginalName(name);
		if (originalName != null) {
			logger.debug("Class rename fallback: '{}' → '{}'", name, originalName);
			cls = findClass(cache, originalName);
			if (cls != null) return cls;
		}

		if (name.contains(".")) {
			int lastDot = name.lastIndexOf('.');
			String altName = name.substring(0, lastDot) + "$" + name.substring(lastDot + 1);
			cls = findClass(cache, altName);
			if (cls != null) return cls;
		}

		for (JavaClass jc : decompiler.getClasses()) {
			if (jc.getFullName().equals(name)) return jc;
		}
		return null;
	}

	/**
	 * 健壮的方法查找：签名分离 → 类名前缀剥离 → 精确匹配 → rename fallback → forceProcess 重试 → 父类/接口搜索
	 */
	public static JavaMethod findMethod(JavaClass cls, String input) {
		String pureName = input;
		String signature = null;

		if (input.contains("(")) {
			int sigIdx = input.indexOf('(');
			signature = input.substring(sigIdx);
			pureName = input.substring(0, sigIdx);
		}

		if (pureName.contains(".")) {
			pureName = pureName.substring(pureName.lastIndexOf('.') + 1);
		}

		JavaMethod found = matchMethodInList(cls.getMethods(), pureName, signature);
		if (found != null) return found;

		String originalName = resolveOriginalName(pureName);
		if (originalName != null) {
			logger.debug("Method rename fallback: '{}' → '{}'", pureName, originalName);
			found = matchMethodInList(cls.getMethods(), originalName, signature);
			if (found != null) return found;
		}

		try {
			cls.getClassNode().unload();
			cls.getClassNode().root().getProcessClasses().forceProcess(cls.getClassNode());
			found = matchMethodInList(cls.getMethods(), pureName, signature);
			if (found != null) return found;
			if (originalName != null) {
				found = matchMethodInList(cls.getMethods(), originalName, signature);
				if (found != null) return found;
			}
		} catch (Exception e) {
			logger.warn("Force process failed for {}: {}", cls.getFullName(), e.getMessage());
		}

		// 父类/接口搜索：方法可能定义在父类中
		found = findMethodInHierarchy(cls, pureName, signature);
		if (found != null) return found;
		if (originalName != null) {
			found = findMethodInHierarchy(cls, originalName, signature);
		}
		return found;
	}

	/**
	 * 遍历继承链搜索方法（最多向上 5 层防止无限循环）
	 */
	private static JavaMethod findMethodInHierarchy(JavaClass cls, String name, String signature) {
		try {
			Set<String> visited = new HashSet<>();
			visited.add(cls.getFullName());
			var classNode = cls.getClassNode();
			for (int depth = 0; depth < 5; depth++) {
				var superType = classNode.getSuperClass();
				if (superType == null || "java.lang.Object".equals(superType.getObject())) break;
				String superName = superType.getObject().replace('/', '.');
				if (visited.contains(superName)) break;
				visited.add(superName);

				if (classCache != null) {
					JavaClass superCls = classCache.get(superName);
					if (superCls != null) {
						JavaMethod found = matchMethodInList(superCls.getMethods(), name, signature);
						if (found != null) return found;
						classNode = superCls.getClassNode();
						continue;
					}
				}
				break;
			}
		} catch (Exception e) {
			logger.debug("Hierarchy search failed for {}: {}", cls.getFullName(), e.getMessage());
		}
		return null;
	}

	/**
	 * 精确签名匹配方法（shortId 匹配）
	 */
	public static JavaMethod findMethodBySig(JavaClass cls, String sig) {
		for (JavaMethod m : cls.getMethods()) {
			try {
				if (m.getMethodNode().getMethodInfo().getShortId().equals(sig)) return m;
			} catch (Exception ignored) {}
		}
		return null;
	}

	/**
	 * 健壮的字段查找：类名前缀剥离 + rename fallback + 父类搜索
	 */
	public static JavaField findField(JavaClass cls, String input) {
		String pureName = input;
		if (pureName.contains(".")) {
			pureName = pureName.substring(pureName.lastIndexOf('.') + 1);
		}
		for (JavaField f : cls.getFields()) {
			if (f.getName().equals(pureName)) return f;
		}
		String originalName = resolveOriginalName(pureName);
		if (originalName != null) {
			logger.debug("Field rename fallback: '{}' → '{}'", pureName, originalName);
			for (JavaField f : cls.getFields()) {
				if (f.getName().equals(originalName)) return f;
			}
		}
		// 父类搜索
		JavaField found = findFieldInHierarchy(cls, pureName);
		if (found != null) return found;
		if (originalName != null) {
			found = findFieldInHierarchy(cls, originalName);
		}
		return found;
	}

	private static JavaField findFieldInHierarchy(JavaClass cls, String name) {
		try {
			Set<String> visited = new HashSet<>();
			visited.add(cls.getFullName());
			var classNode = cls.getClassNode();
			for (int depth = 0; depth < 5; depth++) {
				var superType = classNode.getSuperClass();
				if (superType == null || "java.lang.Object".equals(superType.getObject())) break;
				String superName = superType.getObject().replace('/', '.');
				if (visited.contains(superName)) break;
				visited.add(superName);

				if (classCache != null) {
					JavaClass superCls = classCache.get(superName);
					if (superCls != null) {
						for (JavaField f : superCls.getFields()) {
							if (f.getName().equals(name)) return f;
						}
						classNode = superCls.getClassNode();
						continue;
					}
				}
				break;
			}
		} catch (Exception e) {
			logger.debug("Field hierarchy search failed for {}: {}", cls.getFullName(), e.getMessage());
		}
		return null;
	}

	private static JavaMethod matchMethodInList(List<JavaMethod> methods, String name, String signature) {
		if (signature != null) {
			for (JavaMethod m : methods) {
				if (m.getName().equals(name)) {
					try {
						if (m.getMethodNode().getMethodInfo().getShortId().endsWith(signature)) {
							return m;
						}
					} catch (Exception ignored) {}
				}
			}
		}
		for (JavaMethod m : methods) {
			if (m.getName().equals(name)) return m;
		}
		return null;
	}
}
