# JADX NE-AI-Plugin (v0.1.9)

**JADX NE-AI-Plugin**

- Using For Connect to NER-Agent，reverse engine with local or remote llm

---

## 🛠️ Install & Useage

### 1. Install
In jadx-cli:
```bash
  jadx plugins --install "github:jadx-decompiler:jadx-nr-plugin"
```
### Default Port
- JADX Plugin Port：13997
- Agent Port：13998
- One API Port：13999

### 2. Setting
- **API URL**: `http://localhost:13998/jadxPushCode`

---

## 📖 API

### 1. 源码探索 (Code Insight)

| 路由 | 功能说明 | 核心参数 |
| :--- | :--- | :--- |
| `/getClassCode` | 获取指定类的 Java 源代码 | `class`, `offset`, `limit` |
| `/getAllClasses` | 分页列出 APK 中所有的类名 | `offset`, `limit` |
| `/getClassStructure` | 获取类的成员（字段、方法）声明列表 | `class` |
| `/getClassSmali` | 获取类的 Smali 字节码 | `class` |

### 2. 资源解析 (Resources)

| 路由 | 功能说明 | 核心参数 |
| :--- | :--- | :--- |
| `/getAllResourceNames` | 获取所有资源文件路径列表 | - |
| `/getResourceFile` | 读取资源文件内容 (XML/JSON/Text) | `name` |
| `/getMainAppClasses` | 获取主业务包名下的核心类列表 | `offset`, `limit` |
| `/getMainActivity` | 自动识别并返回入口 Activity 源码 | - |

### 3. 搜索与侦察 (Search & Recon)

| 路由 | 功能说明 | 核心参数 |
| :--- | :--- | :--- |
| `/searchMethod` | 结构化搜索方法名（不触发反编译） | `name` |
| `/searchClass` | 搜索类名（支持模糊匹配） | `query` |
| `/searchString` | **异步**全量源代码字符串搜索 | `query` |
| `/getXrefs` | 获取类/方法/字段的交叉引用 (Find Usages) | `class`, `method`, `field` |
| `/scanCrypto` | **异步**扫描加密算法特征指纹 (AES/RSA/MD5) | - |

### 4. 重构与去混淆 (Refactor)

*重命名操作将实时同步并刷新 JADX GUI 视图*

| 路由 | 功能说明 | 核心参数 |
| :--- | :--- | :--- |
| `/classRename` | 重命名类 | `oldName`, `newName` |
| `/methodRename` | 重命名方法 | `class`, `method`, `newName` |
| `/fieldRename` | 重命名字段 | `class`, `field`, `newName` |
| `/variableRename` | 重命名方法内的局部变量 | `class`, `method`, `oldName`, `newName` |
| `/refactorMapping` | 导出当前 Session 所有的重命名映射表 | - |

### 5. 系统与运维 (Basic)

| 路由 | 功能说明 | 备注 |
| :--- | :--- | :--- |
| `/cacheClear` | 强制清空类缓存与资源缓存 | 执行大规模重命名后建议调用 |
| `/systemStatus` | 获取插件运行状态及 JADX 环境信息 | - |
| `/taskStatus` | 查询异步任务（搜索/扫描）的进度与结果 | `id` (TaskID) |

---
