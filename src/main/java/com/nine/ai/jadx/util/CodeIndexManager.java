package com.nine.ai.jadx.util;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 全局代码索引管理器（单例）。
 * 首次调用 getIndex 时一次性反编译所有类并缓存代码文本，
 * 后续 searchString/scanCrypto/classCodeSearch 直接查内存 map，
 * 避免每次搜索重复调用 cls.getCode() 触发反编译。
 */
public class CodeIndexManager {
	private static final Logger LOG = LoggerFactory.getLogger(CodeIndexManager.class);
	private static final CodeIndexManager INSTANCE = new CodeIndexManager();

	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	private Map<String, String> codeIndex = null; // fullClassName → decompiled code
	private boolean indexed = false;

	private CodeIndexManager() {}

	public static CodeIndexManager getInstance() {
		return INSTANCE;
	}

	/**
	 * 获取全局代码索引。首次调用时构建（可能耗时），后续直接返回缓存。
	 * 线程安全：使用 ReadWriteLock 保证并发读 + 排他写。
	 */
	public Map<String, String> getIndex(JadxDecompiler decompiler) {
		lock.readLock().lock();
		try {
			if (indexed && codeIndex != null) {
				return codeIndex;
			}
		} finally {
			lock.readLock().unlock();
		}

		lock.writeLock().lock();
		try {
			if (indexed && codeIndex != null) {
				return codeIndex; // double-check
			}

			long start = System.currentTimeMillis();
			codeIndex = new HashMap<>();

			for (JavaClass cls : decompiler.getClassesWithInners()) {
				try {
					String code = cls.getCode();
					if (code != null && !code.isEmpty()) {
						codeIndex.put(cls.getFullName(), code);
					}
				} catch (Exception ignored) {
				}
			}

			indexed = true;
			long elapsed = System.currentTimeMillis() - start;
			LOG.info("Code index built: {} classes indexed in {}ms", codeIndex.size(), elapsed);
			return codeIndex;
		} finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * 索引是否已构建
	 */
	public boolean isIndexed() {
		lock.readLock().lock();
		try {
			return indexed;
		} finally {
			lock.readLock().unlock();
		}
	}

	/**
	 * 清除索引。clearCache 时调用，下次搜索将重新构建。
	 */
	public void invalidate() {
		lock.writeLock().lock();
		try {
			indexed = false;
			if (codeIndex != null) {
				codeIndex.clear();
				codeIndex = null;
			}
			LOG.info("Code index invalidated");
		} finally {
			lock.writeLock().unlock();
		}
	}
}
