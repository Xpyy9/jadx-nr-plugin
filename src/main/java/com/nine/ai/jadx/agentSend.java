package com.nine.ai.jadx;

import java.awt.Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import javax.swing.JOptionPane;

import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.plugins.context.GuiPluginContext;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.codearea.AbstractCodeContentPanel;
import jadx.gui.ui.codearea.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class agentSend {
	private static final Logger LOG = LoggerFactory.getLogger(agentSend.class);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final JadxGuiContext guiContext;
	private final agentOption options;

	// 类构造
	public agentSend(JadxGuiContext guiContext, agentOption options) {
		this.guiContext = guiContext;
		this.options = options;
	}

	// 发送agent前数据检查
	public void sendSelectedCodeToAgent(ICodeNodeRef nodeRef) {
		if (this.guiContext == null) return;
		// 代码片段空检查
		String selectedText = getSelectedText();
		if (selectedText == null || selectedText.trim().isEmpty()) {
			showMessage("请先选中要发送的代码片段");
			return;
		}
		// agent url空检查
		String apiUrl = options.getApiUrl();
		if (apiUrl == null || apiUrl.trim().isEmpty()) {
			showMessage("请先在设置中配置Agent API URL");
			return;
		}
		//发送agent操作
		CompletableFuture.runAsync(() -> {
			try {
				sendToAgent(apiUrl, selectedText);
			} catch (Exception e) {
				LOG.error("发送到agent失败", e);
				showMessage("发送失败: " + e.getMessage());
			}
		});
	}

	// 获取选中代码片段
	private String getSelectedText() {
		try {
			GuiPluginContext pluginContext = (GuiPluginContext) guiContext;
			CodeArea codeArea = getCurrentCodeArea(pluginContext);
			if (codeArea != null) {
				String selectedText = (String) codeArea.getClass().getMethod("getSelectedText").invoke(codeArea);
				if (selectedText != null && !selectedText.trim().isEmpty()) {
					return selectedText;
				}
			}
		} catch (Exception e) {
			LOG.error("Failed to get selected text", e);
		}
		return null;
	}

	// 获取当前代码面板的 CodeArea
	private CodeArea getCurrentCodeArea(GuiPluginContext context) {
		Container contentPane = context.getCommonContext()
				.getMainWindow()
				.getTabbedPane()
				.getSelectedContentPanel();
		if (contentPane instanceof AbstractCodeContentPanel) {
			AbstractCodeArea codeArea = ((AbstractCodeContentPanel) contentPane).getCodeArea();
			if (codeArea instanceof CodeArea) {
				return (CodeArea) codeArea;
			}
		}
		return null;
	}

	// 发送到agent
	private void sendToAgent(String apiUrl, String code) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Content-Type", "text/plain; charset=utf-8")
				.header("User-Agent", "JADX-Agent-Plugin/1.0")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(code))
				.build();
		HttpResponse<String> response = HTTP_CLIENT.send(request,
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 200) {
			showMessage("代码已成功发送至agent");
			LOG.info("代码发送成功，响应: {}", response.body());
		} else {
			String errorMsg = String.format("发送失败 (HTTP %d): %s",
					response.statusCode(), response.body());
			showMessage(errorMsg);
			LOG.warn(errorMsg);
		}
	}

	// 消息提示
	private void showMessage(String message) {
		guiContext.uiRun(() -> {
			JOptionPane.showMessageDialog(
					guiContext.getMainFrame(),
					message,
					"AI Agent",
					JOptionPane.INFORMATION_MESSAGE
			);
		});
	}

}
