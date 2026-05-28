package com.nine.ai.jadx;

import com.nine.ai.jadx.core.CodeIndexManager;
import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.JadxUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.ui.MainWindow;

/**
 * NR-AI JADX Plugin entry point.
 * Starts the HTTP analysis server when JADX GUI loads.
 */
public class AiAgentPlugin implements JadxPlugin {
    public static final String PLUGIN_ID = "nr-ai-plugin";
    private static final Logger LOG = LoggerFactory.getLogger(AiAgentPlugin.class);

    @Override
    public JadxPluginInfo getPluginInfo() {
        return new JadxPluginInfo(
                PLUGIN_ID,
                "NR-AI-Plugin",
                "JADX AI Agent Plugin - Security Analysis Server",
                "Nine",
                "2.0.0"
        );
    }

    @Override
    public void init(JadxPluginContext context) {
        JadxGuiContext guiContext = context.getGuiContext();

        if (guiContext == null) {
            LOG.warn("NR-AI Plugin: Running in CLI mode. Plugin disabled (GUI required).");
            return;
        }

        try {
            MainWindow mainWindow = null;
            Object mainFrame = guiContext.getMainFrame();
            if (mainFrame instanceof MainWindow) {
                mainWindow = (MainWindow) mainFrame;
            }

            if (mainWindow == null) {
                LOG.error("NR-AI Plugin: Cannot find JADX MainWindow.");
                return;
            }

            PluginServer server = PluginServer.getInstance(guiContext, mainWindow);
            server.start();

            LOG.info("[+] NR-AI Plugin v2.0 initialized. Server on port 13997 (4 routes)");

        } catch (Exception e) {
            LOG.error("[-] NR-AI Plugin: Critical failure during initialization", e);
        }
    }

    @Override
    public void unload() {
        LOG.info("[*] Unloading NR-AI Plugin...");
        try {
            PluginServer server = PluginServer.getInstance();
            if (server != null) {
                server.stop();
            }
            CodeUtil.clearClassCache();
            CodeIndexManager.getInstance().invalidate();
            JadxUtil.clearCaches();
            LOG.info("[+] NR-AI Plugin resources released.");
        } catch (Exception e) {
            LOG.error("[-] NR-AI Plugin: Error during unloading", e);
        }
    }
}
