package com.nine.ai.jadx.util;

import java.util.*;

/**
 * 安全指纹扫描工具 — 5 大类安全检测。
 * 从代码索引中扫描安全相关特征（不触发反编译）。
 */
public class FingerprintUtil {

	// ==================== Pattern Definitions ====================

	private static final List<ScanPattern> PATTERNS = new ArrayList<>();

	static {
		// Category 1: Weak Crypto
		addPattern("weak_crypto", "high", "DES", "DES");
		addPattern("weak_crypto", "high", "RC4", "RC4");
		addPattern("weak_crypto", "high", "ECB mode", "ECB");
		addPattern("weak_crypto", "high", "MD5", "MessageDigest.getInstance(\"MD5\"");
		addPattern("weak_crypto", "high", "SHA-1", "MessageDigest.getInstance(\"SHA-1\"");
		addPattern("weak_crypto", "medium", "AES without mode", "AES");
		addPattern("weak_crypto", "medium", "javax.crypto.Cipher", "javax.crypto.Cipher");
		addPattern("weak_crypto", "medium", "SecretKeySpec", "SecretKeySpec");
		addPattern("weak_crypto", "info", "MessageDigest", "MessageDigest");
		addPattern("weak_crypto", "info", "getEncoded", "getEncoded");

		// Category 2: Hardcoded Secrets
		addPattern("hardcoded_secrets", "high", "hardcoded password", "password=");
		addPattern("hardcoded_secrets", "high", "hardcoded password", "password =");
		addPattern("hardcoded_secrets", "high", "hardcoded secret", "secret=");
		addPattern("hardcoded_secrets", "high", "hardcoded secret", "secret =");
		addPattern("hardcoded_secrets", "high", "hardcoded API key", "api_key=");
		addPattern("hardcoded_secrets", "high", "hardcoded API key", "api_key =");
		addPattern("hardcoded_secrets", "high", "hardcoded API key", "apiKey=");
		addPattern("hardcoded_secrets", "high", "hardcoded API key", "apikey =");
		addPattern("hardcoded_secrets", "high", "PEM private key", "BEGIN RSA PRIVATE KEY");
		addPattern("hardcoded_secrets", "high", "PEM private key", "BEGIN PRIVATE KEY");
		addPattern("hardcoded_secrets", "medium", "hardcoded token", "token=");
		addPattern("hardcoded_secrets", "medium", "hardcoded access key", "access_key");

		// Category 3: SSL/TLS Issues
		addPattern("ssl_tls", "critical", "custom TrustManager", "X509TrustManager");
		addPattern("ssl_tls", "critical", "trust all certificates", "checkServerTrusted");
		addPattern("ssl_tls", "critical", "AllowAllHostnameVerifier", "AllowAllHostnameVerifier");
		addPattern("ssl_tls", "critical", "ALLOW_ALL_HOSTNAME_VERIFIER", "ALLOW_ALL_HOSTNAME_VERIFIER");
		addPattern("ssl_tls", "high", "custom HostnameVerifier", "HostnameVerifier");
		addPattern("ssl_tls", "high", "custom SSLSocketFactory", "SSLSocketFactory");

		// Category 4: WebView Security
		addPattern("webview", "high", "JavaScript interface", "addJavascriptInterface");
		addPattern("webview", "high", "JavaScript enabled", "setJavaScriptEnabled(true)");
		addPattern("webview", "high", "file access", "setAllowFileAccess(true)");
		addPattern("webview", "high", "universal file access", "setAllowUniversalAccessFromFileURLs");
		addPattern("webview", "medium", "WebView loadUrl", "loadUrl(");
		addPattern("webview", "medium", "WebViewClient override", "WebViewClient");

		// Category 5: Data Leakage
		addPattern("data_leakage", "medium", "external storage", "getExternalStorage");
		addPattern("data_leakage", "medium", "external storage", "EXTERNAL_STORAGE");
		addPattern("data_leakage", "high", "MODE_WORLD_READABLE", "MODE_WORLD_READABLE");
		addPattern("data_leakage", "high", "MODE_WORLD_WRITEABLE", "MODE_WORLD_WRITEABLE");
		addPattern("data_leakage", "medium", "SharedPreferences", "getSharedPreferences");
		addPattern("data_leakage", "medium", "logging sensitive", "Log.d(");
		addPattern("data_leakage", "medium", "clipboard access", "ClipboardManager");
	}

	/**
	 * 从代码索引中扫描安全特征（不触发反编译）。
	 * 每个结果包含 category, severity, pattern_matched, class_name, hint。
	 */
	public static List<Map<String, String>> scanCryptoFromIndex(Map<String, String> codeIndex) {
		List<Map<String, String>> suspects = new ArrayList<>();
		for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
			String code = entry.getValue();
			Set<String> matchedPatterns = new HashSet<>(); // 去重同一个 pattern

			for (ScanPattern p : PATTERNS) {
				if (code.contains(p.signature) && matchedPatterns.add(p.signature)) {
					Map<String, String> item = new LinkedHashMap<>();
					item.put("class_name", entry.getKey());
					item.put("category", p.category);
					item.put("severity", p.severity);
					item.put("pattern_matched", p.name);
					item.put("hint", "Contains " + p.signature);
					suspects.add(item);
				}
			}
		}
		return suspects;
	}

	private static void addPattern(String category, String severity, String name, String signature) {
		PATTERNS.add(new ScanPattern(category, severity, name, signature));
	}

	private static class ScanPattern {
		final String category;
		final String severity;
		final String name;
		final String signature;

		ScanPattern(String category, String severity, String name, String signature) {
			this.category = category;
			this.severity = severity;
			this.name = name;
			this.signature = signature;
		}
	}
}
