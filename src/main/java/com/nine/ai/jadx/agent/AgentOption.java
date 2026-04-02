package com.nine.ai.jadx.agent;

import com.nine.ai.jadx.AiAgentPlugin;
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

public class AgentOption extends BasePluginOptionsBuilder {
	// 默认推送到本地 Agent 的地址
	private static final String DEFAULT_API_URL = "http://localhost:13998/jadxPushCode";
	private String agentApiUrl = DEFAULT_API_URL;

	@Override
	public void registerOptions() {
		strOption(AiAgentPlugin.PLUGIN_ID + ".api-url")
				.description("设置 AI Agent 的接收地址 (HTTP POST)")
				.defaultValue(DEFAULT_API_URL)
				.setter(v -> agentApiUrl = v);
	}

	public String getApiUrl() {
		return (agentApiUrl == null || agentApiUrl.isEmpty()) ? DEFAULT_API_URL : agentApiUrl;
	}
}
