package com.nine.ai.jadx.server.handler.basic;

import com.nine.ai.jadx.util.CodeUtil;
import com.nine.ai.jadx.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public class MappingExportHandler implements HttpHandler {
	/**
	 * 导出全局的“重命名映射表”。该表记录了你（Agent）在此次分析过程中，将哪些原始的混淆名称（如 p001.a.b）改成了什么具有语义的新名称（如 com.app.CryptoUtils）。
	 * 【行动指南】：
	 * 1. 终极收网：逆向分析的最终目的是生成可执行的破解/抓包脚本。请注意：Frida、Xposed 等注入框架在运行时，只认 App 原本的“混淆名”，绝不认识你重命名后的名字！
	 * 2. 何时调用：当你已经完全理清了逻辑，准备开始为用户编写最终的 Frida Hook 脚本或逆向分析总结报告时，必须先调用此工具！
	 * 3. 使用方式：查阅返回的 JSON 字典，在写 Frida 脚本的 `Java.use(...)` 时，将你脑海中的“新名字”替换回对应的“老名字”。
	 * @param exchange
	 * @throws IOException
	 */
	@Override
	public void handle(HttpExchange exchange) throws IOException {
		Map<String, String> mapping = CodeUtil.getRenameMapping();
		StringBuilder sb = new StringBuilder("{");
		mapping.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
		if (sb.length() > 1) sb.setLength(sb.length() - 1);
		sb.append("}");

		HttpUtil.getInstance().sendResponse(exchange, 200, sb.toString());
	}
}
