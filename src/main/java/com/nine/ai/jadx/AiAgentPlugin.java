package com.nine.ai.jadx;

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

public class AiAgentPlugin implements JadxPlugin {
	public static final String PLUGIN_ID = "nr-ai-plugin";
	private static final Logger LOG = LoggerFactory.getLogger(AiAgentPlugin.class);

	@Override
	public JadxPluginInfo getPluginInfo() {
		return new JadxPluginInfo(
				PLUGIN_ID,
				"NR-AI-Plugin",
				"Jadx AI Agent Plugin (Server & Integration)",
				"Nine",
				"1.0.0"
		);
	}

	@Override
	public void init(JadxPluginContext context) {
		JadxGuiContext guiContext = context.getGuiContext();

		// 运行环境检查：非 GUI 模式下不启动
		if (guiContext == null) {
			LOG.warn("NR-AI Plugin: Running in CLI mode. Plugin disabled (GUI required).");
			return;
		}

		try {
			// 获取 MainWindow 引用（重命名功能核心依赖）
			MainWindow mainWindow = null;
			Object mainFrame = guiContext.getMainFrame();
			if (mainFrame instanceof MainWindow) {
				mainWindow = (MainWindow) mainFrame;
			}

			if (mainWindow == null) {
				LOG.error("NR-AI Plugin: Cannot find JADX MainWindow. Renaming features will be unavailable.");
				return;
			}

			// 启动 HTTP 服务端
			PluginServer server = PluginServer.getInstance(guiContext, mainWindow);
			server.start();

			LOG.info("[+] NR-AI Plugin initialized successfully. HTTP Server on port 13997");

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
			JadxUtil.clearCaches();
			LOG.info("[+] NR-AI Plugin resources released successfully.");
		} catch (Exception e) {
			LOG.error("[-] NR-AI Plugin: Error during unloading", e);
		}
	}
}
