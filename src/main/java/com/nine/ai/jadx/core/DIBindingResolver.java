package com.nine.ai.jadx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2: DI 绑定解析器。
 * 在 CodeIndex 遍历时扫描 Dagger/Hilt @Module 类的 @Provides/@Binds 声明，
 * 建立 interface → implementation 映射。
 *
 * 用途：
 * - 当 agent 看到一个接口调用时，能直接查询实际实现类
 * - 支持 AnalyzeHandler resolveDI action
 */
public class DIBindingResolver {
    private static final Logger LOG = LoggerFactory.getLogger(DIBindingResolver.class);

    // interface/abstract → implementation class
    private final ConcurrentHashMap<String, Set<String>> bindings = new ConcurrentHashMap<>();

    // module class → list of provides/binds declarations
    private final ConcurrentHashMap<String, List<DIBinding>> moduleBindings = new ConcurrentHashMap<>();

    // All known module classes
    private final Set<String> moduleClasses = ConcurrentHashMap.newKeySet();

    // All known @Inject constructors
    private final ConcurrentHashMap<String, List<String>> injectConstructors = new ConcurrentHashMap<>();

    // ==================== Patterns ====================

    // Detect @Module annotation
    private static final Pattern MODULE_ANNOTATION = Pattern.compile("@Module\\b");

    // Detect @Provides method: @Provides ReturnType methodName(...)
    private static final Pattern PROVIDES_PATTERN = Pattern.compile(
            "@Provides\\s+(?:@\\w+\\s+)*([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    // Detect @Binds method: @Binds InterfaceType methodName(ImplementationType impl)
    private static final Pattern BINDS_PATTERN = Pattern.compile(
            "@Binds\\s+(?:@\\w+\\s+)*([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(\\s*([\\w.<>\\[\\]]+)\\s+\\w+\\s*\\)");

    // Detect @Inject constructor
    private static final Pattern INJECT_CONSTRUCTOR = Pattern.compile(
            "@Inject\\s+(?:public\\s+)?(\\w+)\\s*\\(([^)]*)\\)");

    // Detect implements/extends (to map interface → implementation)
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile(
            "class\\s+(\\w+)(?:\\s+extends\\s+\\w+)?\\s+implements\\s+([\\w,\\s]+)");

    // Detect return statement with new Impl() in @Provides
    private static final Pattern PROVIDES_RETURN_NEW = Pattern.compile(
            "return\\s+new\\s+([\\w.]+)\\s*\\(");

    // ==================== Build API ====================

    /**
     * Scan a class source code for DI bindings.
     * Called during CodeIndex traversal.
     *
     * @param className Full class name
     * @param sourceCode Decompiled source code
     */
    public void scanClass(String className, String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) return;

        // Check if this is a @Module class
        if (MODULE_ANNOTATION.matcher(sourceCode).find()) {
            moduleClasses.add(className);
            scanModule(className, sourceCode);
        }

        // Check for @Inject constructors (in any class)
        scanInjectConstructors(className, sourceCode);

        // Check for implements declarations (helps resolve bindings)
        scanImplements(className, sourceCode);
    }

    private void scanModule(String className, String sourceCode) {
        List<DIBinding> found = new ArrayList<>();

        // Scan @Provides
        Matcher providesMatcher = PROVIDES_PATTERN.matcher(sourceCode);
        while (providesMatcher.find()) {
            DIBinding binding = new DIBinding();
            binding.moduleClass = className;
            binding.type = "provides";
            binding.returnType = providesMatcher.group(1);
            binding.methodName = providesMatcher.group(2);
            binding.parameters = providesMatcher.group(3).trim();

            // Try to find what implementation is returned
            int methodStart = providesMatcher.end();
            int methodEnd = findMethodEnd(sourceCode, methodStart);
            if (methodEnd > methodStart) {
                String methodBody = sourceCode.substring(methodStart, methodEnd);
                Matcher returnMatcher = PROVIDES_RETURN_NEW.matcher(methodBody);
                if (returnMatcher.find()) {
                    binding.implementationType = returnMatcher.group(1);
                    bindings.computeIfAbsent(binding.returnType, k -> ConcurrentHashMap.newKeySet())
                            .add(binding.implementationType);
                }
            }

            found.add(binding);
        }

        // Scan @Binds
        Matcher bindsMatcher = BINDS_PATTERN.matcher(sourceCode);
        while (bindsMatcher.find()) {
            DIBinding binding = new DIBinding();
            binding.moduleClass = className;
            binding.type = "binds";
            binding.returnType = bindsMatcher.group(1);  // interface
            binding.methodName = bindsMatcher.group(2);
            binding.implementationType = bindsMatcher.group(3);  // implementation

            bindings.computeIfAbsent(binding.returnType, k -> ConcurrentHashMap.newKeySet())
                    .add(binding.implementationType);

            found.add(binding);
        }

        if (!found.isEmpty()) {
            moduleBindings.put(className, found);
        }
    }

    private void scanInjectConstructors(String className, String sourceCode) {
        Matcher matcher = INJECT_CONSTRUCTOR.matcher(sourceCode);
        while (matcher.find()) {
            String constructorClass = matcher.group(1);
            String params = matcher.group(2).trim();

            List<String> dependencies = new ArrayList<>();
            if (!params.isEmpty()) {
                for (String param : params.split(",")) {
                    String trimmed = param.trim();
                    // Extract type from "Type name" or "@Annotation Type name"
                    String[] parts = trimmed.split("\\s+");
                    for (String part : parts) {
                        if (!part.startsWith("@") && Character.isUpperCase(part.charAt(0))) {
                            dependencies.add(part);
                            break;
                        }
                    }
                }
            }

            // Use full class name if constructor class matches simple name
            String simpleClassName = className.contains(".") ?
                    className.substring(className.lastIndexOf('.') + 1) : className;
            String key = constructorClass.equals(simpleClassName) ? className : constructorClass;
            injectConstructors.put(key, dependencies);
        }
    }

    private void scanImplements(String className, String sourceCode) {
        Matcher matcher = IMPLEMENTS_PATTERN.matcher(sourceCode);
        while (matcher.find()) {
            String implClass = matcher.group(1);
            String interfaces = matcher.group(2);
            for (String iface : interfaces.split(",")) {
                String trimmed = iface.trim();
                if (!trimmed.isEmpty()) {
                    bindings.computeIfAbsent(trimmed, k -> ConcurrentHashMap.newKeySet())
                            .add(className);
                }
            }
        }
    }

    // ==================== Query API ====================

    /**
     * Resolve an interface to its implementation(s).
     *
     * @param interfaceName Simple or full name of the interface
     * @return Set of implementation class names
     */
    public Set<String> resolveInterface(String interfaceName) {
        // Try exact match first
        Set<String> impls = bindings.get(interfaceName);
        if (impls != null && !impls.isEmpty()) {
            return Collections.unmodifiableSet(impls);
        }

        // Try simple name match
        for (Map.Entry<String, Set<String>> entry : bindings.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("." + interfaceName) || key.equals(interfaceName)) {
                return Collections.unmodifiableSet(entry.getValue());
            }
        }

        return Collections.emptySet();
    }

    /**
     * Get all DI bindings for a module class.
     */
    public List<DIBinding> getModuleBindings(String moduleClassName) {
        List<DIBinding> list = moduleBindings.get(moduleClassName);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /**
     * Get all known module classes.
     */
    public Set<String> getModuleClasses() {
        return Collections.unmodifiableSet(moduleClasses);
    }

    /**
     * Get constructor dependencies for a class (via @Inject).
     */
    public List<String> getConstructorDependencies(String className) {
        List<String> deps = injectConstructors.get(className);
        return deps != null ? Collections.unmodifiableList(deps) : Collections.emptyList();
    }

    /**
     * Get all bindings as a serializable map for JSON response.
     */
    public Map<String, Object> getBindingsAsMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : bindings.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    /**
     * Get DI summary for system status.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("module_classes", moduleClasses.size());
        summary.put("total_bindings", bindings.size());
        summary.put("inject_constructors", injectConstructors.size());
        return summary;
    }

    public void clear() {
        bindings.clear();
        moduleBindings.clear();
        moduleClasses.clear();
        injectConstructors.clear();
    }

    // ==================== Internal ====================

    private int findMethodEnd(String source, int startPos) {
        // Find matching closing brace from startPos
        int braceCount = 0;
        boolean started = false;
        for (int i = startPos; i < Math.min(startPos + 1000, source.length()); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                braceCount++;
                started = true;
            } else if (c == '}') {
                braceCount--;
                if (started && braceCount == 0) {
                    return i;
                }
            }
        }
        return startPos + 500; // fallback
    }

    // ==================== Inner type ====================

    public static class DIBinding {
        public String moduleClass;
        public String type;             // "provides" or "binds"
        public String returnType;       // interface/abstract type
        public String implementationType; // concrete implementation
        public String methodName;
        public String parameters;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("module", moduleClass);
            map.put("type", type);
            map.put("return_type", returnType);
            if (implementationType != null) {
                map.put("implementation", implementationType);
            }
            map.put("method", methodName);
            return map;
        }
    }
}
