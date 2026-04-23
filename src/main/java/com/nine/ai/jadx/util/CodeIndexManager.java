package com.nine.ai.jadx.util;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
	private final AtomicInteger indexedCount = new AtomicInteger(0);
	private int totalClasses = 0;

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

			var classList = decompiler.getClassesWithInners();
			totalClasses = classList.size();
			indexedCount.set(0);

			for (JavaClass cls : classList) {
				try {
					String code = cls.getCode();
					if (code != null && !code.isEmpty()) {
						codeIndex.put(cls.getFullName(), code);
					}
				} catch (Exception ignored) {
				}
				indexedCount.incrementAndGet();
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
	 * 获取索引构建进度（百分比 0-100）。-1 表示未开始。
	 */
	public int getProgress() {
		if (indexed) return 100;
		if (totalClasses == 0) return -1;
		return (int) ((indexedCount.get() * 100L) / totalClasses);
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

	/**
	 * 内存压力检查：当 JVM 堆使用 > 85% 时自动回收 code index 并 GC。
	 * 在搜索操作完成后调用。
	 */
	public void trimIfNeeded() {
		Runtime rt = Runtime.getRuntime();
		long maxMem = rt.maxMemory();
		long usedMem = rt.totalMemory() - rt.freeMemory();
		double usage = (usedMem * 100.0) / maxMem;

		if (usage > 85 && indexed) {
			LOG.warn("Memory pressure critical ({}%), invalidating code index to free memory", String.format("%.1f", usage));
			invalidate();
			System.gc();
		}
	}
}
