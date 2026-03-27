package com.nine.ai.jadx;

import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

public class agentOption extends BasePluginOptionsBuilder {
	private static final String DEFAULT_API_URL = "http://localhost:13998/jadxPushCode";
	private String agentApiUrl = DEFAULT_API_URL;
	@Override
	public void registerOptions() {
		strOption(AiAgentPlugin.PLUGIN_ID + ".api-url")
				.description("AI Agent API URL")
				.defaultValue(DEFAULT_API_URL)
				.setter(v -> agentApiUrl = v);
	}

	public String getApiUrl() {
		return agentApiUrl;
	}
}
