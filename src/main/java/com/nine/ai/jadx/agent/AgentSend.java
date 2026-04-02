package com.nine.ai.jadx.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.swing.JOptionPane;

import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.CodeArea;
import jadx.gui.ui.panel.ContentPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentSend {
	private static final Logger LOG = LoggerFactory.getLogger(AgentSend.class);

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private final JadxGuiContext guiContext;
	private final AgentOption options;

	public AgentSend(JadxGuiContext guiContext, AgentOption options) {
		this.guiContext = guiContext;
		this.options = options;
	}

	/**
	 * 发送选中的代码到 Agent
	 */
	public void sendSelectedCodeToAgent(ICodeNodeRef nodeRef) {
		if (this.guiContext == null) return;

		String selectedText = getSelectedText();
		if (selectedText == null || selectedText.trim().isEmpty()) {
			showNotification("提示", "请先在代码窗口中选中要分析的代码片段", JOptionPane.WARNING_MESSAGE);
			return;
		}

		String apiUrl = options.getApiUrl();
		String contextName = (nodeRef != null) ? nodeRef.toString() : "Unknown Context";

		CompletableFuture.runAsync(() -> {
			try {
				String jsonPayload = String.format(
						"{\"context\":\"%s\", \"code\":\"%s\"}",
						escapeJson(contextName),
						escapeJson(selectedText)
				);

				sendToAgent(apiUrl, jsonPayload);
			} catch (Exception e) {
				LOG.error("Failed to push code to agent", e);
				showNotification("发送失败", e.getMessage(), JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	/**
	 * 通过 MainWindow -> TabbedPane -> SelectedContentPanel 手动提取 CodeArea
	 */
	private String getSelectedText() {
		try {
			Object mainFrame = guiContext.getMainFrame();
			if (mainFrame instanceof MainWindow) {
				MainWindow mw = (MainWindow) mainFrame;
				ContentPanel selectedPanel = mw.getTabbedPane().getSelectedContentPanel();
				if (selectedPanel instanceof jadx.gui.ui.codearea.AbstractCodeContentPanel) {
					jadx.gui.ui.codearea.AbstractCodeArea abstractCodeArea =
							((jadx.gui.ui.codearea.AbstractCodeContentPanel) selectedPanel).getCodeArea();
					if (abstractCodeArea instanceof CodeArea) {
						CodeArea codeArea = (CodeArea) abstractCodeArea;
						return codeArea.getSelectedText();
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Failed to get selected text: {}", e.getMessage());
		}
		return null;
	}

	private void sendToAgent(String apiUrl, String jsonBody) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Content-Type", "application/json; charset=utf-8")
				.header("User-Agent", "JADX-AI-Plugin/1.0")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 200 && response.statusCode() < 300) {
			showNotification("AI Agent", "代码已推送", JOptionPane.INFORMATION_MESSAGE);
		} else {
			throw new RuntimeException("HTTP " + response.statusCode());
		}
	}

	private void showNotification(String title, String msg, int type) {
		guiContext.uiRun(() -> {
			JOptionPane.showMessageDialog(guiContext.getMainFrame(), msg, title, type);
		});
	}

	private String escapeJson(String input) {
		if (input == null) return "";
		return input.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\b", "\\b")
				.replace("\f", "\\f")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
