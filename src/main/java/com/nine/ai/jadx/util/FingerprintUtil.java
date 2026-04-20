package com.nine.ai.jadx.util;

import java.util.*;

public class FingerprintUtil {
	private static final List<String> CRYPTO_SIGS = Arrays.asList(
			"javax.crypto.Cipher", "SecretKeySpec", "MessageDigest", "getEncoded"
	);

	/**
	 * 从代码索引中扫描加密特征（不触发反编译）
	 */
	public static List<Map<String, String>> scanCryptoFromIndex(Map<String, String> codeIndex) {
		List<Map<String, String>> suspects = new ArrayList<>();
		for (Map.Entry<String, String> entry : codeIndex.entrySet()) {
			String code = entry.getValue();
			for (String sig : CRYPTO_SIGS) {
				if (code.contains(sig)) {
					Map<String, String> item = new HashMap<>();
					item.put("class", entry.getKey());
					item.put("type", "CRYPTO_SENSITIVE");
					item.put("hint", "Contains " + sig);
					suspects.add(item);
					break;
				}
			}
		}
		return suspects;
	}}
