package com.nine.ai.jadx.server.handler.code;

import com.nine.ai.jadx.server.handler.basic.BaseDispatcherHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

public class CodeInsightHandler extends BaseDispatcherHandler {

	private final HttpHandler allClassHandler = new AllClassHandler();
	private final HttpHandler classHandler = new ClassHandler();
	private final HttpHandler structureHandler = new ClassStructureHandler();
	private final HttpHandler smaliHandler = new SmaliHandler();

	@Override
	protected void dispatch(HttpExchange exchange, String action, Map<String, String> params) throws IOException {
		switch (action) {
			case "getAllClasses":
				allClassHandler.handle(exchange);
				break;
			case "getClassCode":
				classHandler.handle(exchange);
				break;
			case "getClassStructure":
				structureHandler.handle(exchange);
				break;
			case "getClassSmali":
				smaliHandler.handle(exchange);
				break;
			case "getClassWithStructure":
				CompositeHandler.handleClassWithStructure(exchange);
				break;
			case "batchGetClassCode":
				CompositeHandler.handleBatchGetClassCode(exchange);
				break;
			case "getMethodWithCallers":
				CompositeHandler.handleMethodWithCallers(exchange);
				break;
			case "getMethodCode":
				CompositeHandler.handleMethodCode(exchange);
				break;
			default:
				http.sendError(exchange, 400, "Invalid action: " + action);
		}
	}
}
