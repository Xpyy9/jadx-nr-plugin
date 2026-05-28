package com.nine.ai.jadx.util;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 共享 Manifest 解析工具类。
 * 提供基础 XML 属性提取和组件名解析，供 ManifestAnalyzer 和其他组件复用。
 */
public class ManifestParser {

	private static final Pattern PKG_PATTERN = Pattern.compile("<manifest[^>]+package\\s*=\\s*\"([^\"]+)\"");
	private static final Pattern ATTR_NAME = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");

	/**
	 * 查找 AndroidManifest.xml 资源并返回其文本内容。
	 * Uses defensive copy of resource list to avoid ConcurrentModificationException
	 * if JADX is still building its internal structures.
	 */
	public static String getManifestXml(JadxDecompiler decompiler) {
		// Defensive copy: decompiler.getResources() may return a live list
		List<ResourceFile> resources = new ArrayList<>(decompiler.getResources());
		for (ResourceFile res : resources) {
			if ("AndroidManifest.xml".equals(res.getOriginalName())) {
				return JadxUtil.getResourceContent(res);
			}
		}
		return null;
	}

	/**
	 * 从 Manifest XML 中提取 package 名。
	 */
	public static String extractPackageName(String xml) {
		Matcher m = PKG_PATTERN.matcher(xml);
		return m.find() ? m.group(1) : "";
	}

	/**
	 * 规范化组件名：处理 "." 前缀和无包名的短名。
	 */
	public static String normalize(String pkg, String cls) {
		if (cls.startsWith(".")) return pkg + cls;
		if (!cls.contains(".")) return pkg + "." + cls;
		return cls;
	}

	/**
	 * 从 XML 属性块中提取指定 Pattern 的第一个匹配值。
	 */
	public static String extractAttr(String block, Pattern pattern) {
		Matcher m = pattern.matcher(block);
		return m.find() ? m.group(1) : "";
	}

	/**
	 * 提取指定标签的所有组件名称（仅名称列表）。
	 */
	public static List<String> extractComponentNames(String xml, String tagPrefix, String pkg) {
		List<String> result = new ArrayList<>();
		String[] blocks = xml.split(tagPrefix);
		for (int i = 1; i < blocks.length; i++) {
			String block = blocks[i];
			int closeIdx = block.indexOf('>');
			String attrs = closeIdx > 0 ? block.substring(0, closeIdx) : block;
			Matcher m = ATTR_NAME.matcher(attrs);
			if (m.find()) {
				result.add(normalize(pkg, m.group(1)));
			}
		}
		return result;
	}

	/**
	 * 提取 uses-permission 列表。
	 */
	public static List<String> extractUsesPermissions(String xml) {
		List<String> perms = new ArrayList<>();
		Pattern p = Pattern.compile("<uses-permission[^>]+android:name\\s*=\\s*\"([^\"]+)\"");
		Matcher m = p.matcher(xml);
		while (m.find()) {
			perms.add(m.group(1));
		}
		return perms;
	}
}
