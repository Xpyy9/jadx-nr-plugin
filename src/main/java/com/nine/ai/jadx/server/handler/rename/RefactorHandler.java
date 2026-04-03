package com.nine.ai.jadx.server.handler.rename;

import com.nine.ai.jadx.server.PluginServer;
import com.nine.ai.jadx.server.handler.basic.MappingExportHandler;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class RefactorHandler implements HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(RefactorHandler.class);
	private final HttpUtil http = HttpUtil.getInstance();

	// 声明旧的 Handler
	private final HttpHandler classRenameHandler;
	private final HttpHandler methodRenameHandler;
	private final HttpHandler fieldRenameHandler;
	private final HttpHandler variableRenameHandler;
	private final HttpHandler mappingExportHandler;

	public RefactorHandler(MainWindow mainWindow) {
		// 初始化旧的 Handler，传入它们所需的 mainWindow
		this.classRenameHandler = new ClassRenameHandler(mainWindow);
		this.methodRenameHandler = new MethodRenameHandler(mainWindow);
		this.fieldRenameHandler = new FieldRenameHandler(mainWindow);
		this.variableRenameHandler = new VariableRenameHandler(mainWindow);
		this.mappingExportHandler = new MappingExportHandler();
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (!PluginServer.getInstance().isRunning()) {
			http.sendResponse(exchange, 503, "Service unavailable");
			return;
		}

		try {
			Map<String, String> params = http.parseParams(exchange.getRequestURI().getQuery());
			String action = params.get("action");

			if (action == null || action.isBlank()) {
				http.sendResponse(exchange, 400, "Missing required parameter: 'action'");
				return;
			}

			switch (action) {
				case "renameClass":
					classRenameHandler.handle(exchange);
					break;
				case "renameMethod":
					methodRenameHandler.handle(exchange);
					break;
				case "renameField":
					fieldRenameHandler.handle(exchange);
					break;
				case "renameVariable":
					variableRenameHandler.handle(exchange);
					break;
				case "exportMapping":
					mappingExportHandler.handle(exchange);
					break;
				default:
					http.sendResponse(exchange, 400, "Invalid action: " + action);
			}
		} catch (Exception e) {
			logger.error("Refactor Dispatcher failed", e);
			http.sendResponse(exchange, 500, "Dispatcher Error: " + e.getMessage());
		}
	}
}
