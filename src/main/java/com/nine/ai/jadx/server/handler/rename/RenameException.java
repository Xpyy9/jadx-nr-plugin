package com.nine.ai.jadx.server.handler.rename;

/**
 * 重命名操作中携带 HTTP 状态码的业务异常，
 * 由 BaseRenameHandler 统一处理，保证错误码与原始行为一致。
 */
public class RenameException extends RuntimeException {
	private final int statusCode;

	public RenameException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public static RenameException badRequest(String message) {
		return new RenameException(400, message);
	}

	public static RenameException notFound(String message) {
		return new RenameException(404, message);
	}

	public static RenameException internal(String message) {
		return new RenameException(500, message);
	}
}
