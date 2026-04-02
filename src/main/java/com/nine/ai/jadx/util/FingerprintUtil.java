package com.nine.ai.jadx.util;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import java.util.*;

public class FingerprintUtil {
	// 特征扫描与指纹接口
	private static final List<String> CRYPTO_SIGS = Arrays.asList(
			"javax.crypto.Cipher", "SecretKeySpec", "MessageDigest", "getEncoded"
	);

	public static List<Map<String, String>> scanCryptoHinter(List<JavaClass> classes) {
		List<Map<String, String>> suspects = new ArrayList<>();
		for (JavaClass cls : classes) {
			try {
				String code = cls.getCode();
				if (code == null) continue;

				for (String sig : CRYPTO_SIGS) {
					if (code.contains(sig)) {
						Map<String, String> item = new HashMap<>();
						item.put("class", cls.getFullName());
						item.put("type", "CRYPTO_SENSITIVE");
						item.put("hint", "Contains " + sig);
						suspects.add(item);
						break;
					}
				}
			} catch (Exception ignored) {}
		}
		return suspects;
	}
}
