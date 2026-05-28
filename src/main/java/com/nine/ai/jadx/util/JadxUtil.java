package com.nine.ai.jadx.util;

import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.server.PluginServer;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.plugins.context.GuiPluginContext;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JadxUtil {
    private static final Logger LOG = LoggerFactory.getLogger(JadxUtil.class);

    private static Map<String, ResourceFile> resourceCache = new HashMap<>();
    private static volatile JadxDecompiler cachedDecompiler = null;

    // ====================== 获取反编译器 ======================

    public static JadxDecompiler getDecompiler() {
        return getDecompiler(true);
    }

    public static JadxDecompiler getDecompiler(boolean logError) {
        JadxDecompiler cached = cachedDecompiler;
        if (cached != null) {
            return cached;
        }

        try {
            PluginServer server = PluginServer.getInstance();
            if (server == null) return null;

            JadxGuiContext guiContext = server.getGuiContext();
            if (!(guiContext instanceof GuiPluginContext)) {
                return null;
            }

            GuiPluginContext ctx = (GuiPluginContext) guiContext;
            MainWindow mainWindow = ctx.getCommonContext().getMainWindow();
            if (mainWindow == null) return null;

            Object wrapper = mainWindow.getWrapper();
            if (wrapper == null) return null;

            Method method = wrapper.getClass().getMethod("getDecompiler");
            JadxDecompiler decompiler = (JadxDecompiler) method.invoke(wrapper);

            if (decompiler != null) {
                cachedDecompiler = decompiler;
            }
            return decompiler;
        } catch (Exception e) {
            if (logError) {
                LOG.error("Failed to get JadxDecompiler", e);
            }
            return null;
        }
    }

    // ====================== 资源缓存 ======================

    public static synchronized Map<String, ResourceFile> getResourceCache(JadxDecompiler decompiler) {
        if (decompiler == null) return new HashMap<>();
        if (resourceCache.isEmpty()) {
            // Defensive copy to avoid ConcurrentModificationException
            List<ResourceFile> resources = new java.util.ArrayList<>(decompiler.getResources());
            for (ResourceFile res : resources) {
                if (res == null) continue;
                String orig = res.getOriginalName();
                String deobf = res.getDeobfName();
                if (orig != null) resourceCache.put(orig, res);
                if (deobf != null) resourceCache.put(deobf, res);
                String uni = (deobf != null ? deobf : orig).replace('\\', '/');
                resourceCache.put(uni, res);
                resourceCache.put(uni.toLowerCase(), res);
            }
        }
        return resourceCache;
    }

    // ====================== 清空缓存 ======================

    public static synchronized void clearCaches() {
        cachedDecompiler = null;
        if (resourceCache != null) {
            resourceCache.clear();
        }
        CodeUtil.clearClassCache();
        CodeIndexManager.getInstance().invalidate();
        LOG.info("JadxUtil: All caches cleared");
    }

    public static String getResourceContent(ResourceFile res) {
        if (res == null) return null;
        try {
            var content = res.loadContent();
            if (content == null) return null;
            var text = content.getText();
            if (text != null) return text.getCodeStr();
            return content.toString();
        } catch (Exception e) {
            // Check if this is a ConcurrentModificationException (possibly wrapped in JadxException).
            // If so, propagate it so the pipeline retry logic can handle it.
            if (isCausedByConcurrentModification(e)) {
                throw new ConcurrentModificationException("Resource decode race condition: " + e.getMessage(), e);
            }
            LOG.error("Failed to read resource: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isCausedByConcurrentModification(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ConcurrentModificationException) return true;
            current = current.getCause();
        }
        return false;
    }
}
