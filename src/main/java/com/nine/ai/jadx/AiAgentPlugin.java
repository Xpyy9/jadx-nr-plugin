package com.nine.ai.jadx;

import com.nine.ai.jadx.agent.AgentOption;
import com.nine.ai.jadx.agent.AgentSend;
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

	private final AgentOption options = new AgentOption();
	private AgentSend agentSend;
	private JadxPluginContext pluginContext;

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
		this.pluginContext = context;
		JadxGuiContext guiContext = context.getGuiContext();

		// 1. 运行环境检查：非 GUI 模式下不启动 AI 交互功能
		if (guiContext == null) {
			LOG.warn("NR-AI Plugin: Running in CLI mode. Plugin disabled (GUI required).");
			return;
		}

		try {
			// 2. 获取 MainWindow 引用（重命名功能核心依赖）
			MainWindow mainWindow = null;
			Object mainFrame = guiContext.getMainFrame();
			if (mainFrame instanceof MainWindow) {
				mainWindow = (MainWindow) mainFrame;
			}

			if (mainWindow == null) {
				LOG.error("NR-AI Plugin: Cannot find JADX MainWindow. Renaming features will be unavailable.");
				return;
			}

			// 3. 注册配置项
			context.registerOptions(options);

			// 4. 初始化 AI 发送模块与快捷键集成
			this.agentSend = new AgentSend(guiContext, options);
			registerUIComponents(guiContext);

			// 5. 启动 HTTP 服务端
			// 传入 guiContext 和 mainWindow 构造单例，开启 Agent 的远程调用接口
			PluginServer server = PluginServer.getInstance(guiContext, mainWindow);
			server.start();

			LOG.info("[+] NR-AI Plugin initialized successfully. HTTP Server on port 13997 ✅");

		} catch (Exception e) {
			LOG.error("[-] NR-AI Plugin: Critical failure during initialization", e);
		}
	}

	/**
	 * 注册右键菜单与交互组件
	 */
	private void registerUIComponents(JadxGuiContext guiContext) {
		try {
			guiContext.addPopupMenuAction(
					"Send To Agent (AI 分析)",
					node -> true, // 允许所有节点
					"ctrl shift N",
					agentSend::sendSelectedCodeToAgent
			);
		} catch (Exception e) {
			LOG.warn("NR-AI Plugin: Failed to register popup menu action: {}", e.getMessage());
		}
	}

	@Override
	public void unload() {
		LOG.info("[*] Unloading NR-AI Plugin...");
		try {
			// 1. 停止 HTTP 服务
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
