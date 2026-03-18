package com.nine.ai.jadx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;

public class AiAgentPlugin implements JadxPlugin {

	public static final String PLUGIN_ID = "nr-ai-plugin";
	private JadxPluginContext context;
	private JadxGuiContext guiContext;
	private final agentOption options = new agentOption();
	private agentSend agentSend;
	private pluginServer pluginServer;
	private static final Logger LOG = LoggerFactory.getLogger(pluginServer.class);

	@Override
	public JadxPluginInfo getPluginInfo() {
		// 插件基础信息
		return new JadxPluginInfo(
				PLUGIN_ID,
				"NR",
				"Plugin connect Eino-Agent",
				"Nine",
				"Niner");
	}

	@Override
	public void init(JadxPluginContext context) {
		// 插件初始化
		this.context = context;
		this.context.registerOptions(this.options);
		this.guiContext = context.getGuiContext();
		// GUI存在再注册
		if (this.guiContext != null) {
			this.agentSend = new agentSend(this.guiContext, this.options);
			// 注册代码区域的右键菜单
			this.guiContext.addPopupMenuAction(
					"Send Agent",
					node -> true,
					"ctrl shift N",
					this.agentSend::sendSelectedCodeToAgent
			);
			this.pluginServer = new pluginServer(this.guiContext); // 默认构造参数 端口13997 默认单线程 5分钟缓存过期
			this.pluginServer.start();
			LOG.info("[+]===NR-Plugin Start===");
		}
	}

	@Override
	public void unload() {
		// 插件清理逻辑
		// 停止服务器
		if (this.pluginServer != null) {
			this.pluginServer.stop();
		}
	}
}
