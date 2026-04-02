package com.nine.ai.jadx.util;

import com.nine.ai.jadx.server.PluginServer;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.plugins.context.GuiPluginContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class JadxUtil {
	private static final Logger LOG = LoggerFactory.getLogger(JadxUtil.class);

	// 全局缓存
	private static Map<String, ResourceFile> resourceCache = new HashMap<>();
	private static long lastCacheUpdate = 0;
	private static final long CACHE_TIMEOUT = 5 * 60 * 1000; // 5分钟

	// ====================== 获取反编译器 ======================
	public static JadxDecompiler getDecompiler() {
		try {
			JadxGuiContext guiContext = PluginServer.getInstance().getGuiContext();
			if (!(guiContext instanceof GuiPluginContext)) {
				return null;
			}

			GuiPluginContext ctx = (GuiPluginContext) guiContext;
			MainWindow mainWindow = ctx.getCommonContext().getMainWindow();
			if (mainWindow == null) {
				return null;
			}

			Object wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				return null;
			}

			Method method = wrapper.getClass().getMethod("getDecompiler");
			return (JadxDecompiler) method.invoke(wrapper);
		} catch (Exception e) {
			LOG.error("获取 JadxDecompiler 失败", e);
			return null;
		}
	}

	// ====================== 资源缓存 ======================
	public static synchronized Map<String, ResourceFile> getResourceCache(JadxDecompiler decompiler) {
		if (decompiler == null) {
			return new HashMap<>();
		}
		if (resourceCache.isEmpty() || System.currentTimeMillis() - lastCacheUpdate > CACHE_TIMEOUT) {
			resourceCache.clear();
			for (ResourceFile res : decompiler.getResources()) {
				if (res == null) continue;

				String orig = res.getOriginalName();
				String deobf = res.getDeobfName();

				if (orig != null) resourceCache.put(orig, res);
				if (deobf != null) resourceCache.put(deobf, res);

				// 兼容路径
				String uni = (deobf != null ? deobf : orig).replace('\\', '/');
				resourceCache.put(uni, res);
				resourceCache.put(uni.toLowerCase(), res);
			}
			lastCacheUpdate = System.currentTimeMillis();
		}
		return resourceCache;
	}

	// ====================== 清空缓存 ======================
	public static synchronized void clearCaches() {
		if (resourceCache != null) {
			resourceCache.clear();
		}
		lastCacheUpdate = 0;
		CodeUtil.clearClassCache();
		LOG.info("JadxUtil 资源与代码缓存已全面清空");
	}

	public static String getResourceContent(ResourceFile res) {
		if (res == null) return null;
		try {
			Method loadContent = res.getClass().getMethod("loadContent");
			Object content = loadContent.invoke(res);

			try {
				Object text = content.getClass().getMethod("getText").invoke(content);
				if (text != null) {
					return (String) text.getClass().getMethod("getCodeStr").invoke(text);
				}
			} catch (Exception ignored) {
			}

			try {
				return (String) content.getClass().getMethod("getCodeStr").invoke(content);
			} catch (Exception ignored) {
			}

			return content.toString();
		} catch (Exception e) {
			LOG.error("读取资源失败", e);
			return "// [WARNING] JADX failed to parse or read this resource content. File might be corrupted or unsupported.";
		}
	}

	public static String getArscResourceContent(ResourceFile arscFile, String targetFileName) {
		if (arscFile == null) return null;
		try {
			var content = arscFile.loadContent();
			if (content == null || content.getSubFiles() == null) return null;

			for (var subFile : content.getSubFiles()) {
				if (subFile != null && targetFileName.equals(subFile.getFileName())) {
					var text = subFile.getText();
					return text != null ? text.getCodeStr() : null;
				}
			}
		} catch (Exception ignored) {
			LOG.error("读取 arsc 子文件失败: " + targetFileName);
		}
		return null;
	}
}
