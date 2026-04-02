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

## 📖 API 使用说明 (核心接口)

### 1. 系统管理 (System)
- `GET /status`: 获取插件运行状态及 JADX 核心信息。
- `POST /cache/clear`: 强制清空类缓存与资源缓存，常用于代码重命名后重新同步数据。

### 2. 代码提取 (Code & Structure)
- `GET /getClassCode?class={fullName}&offset=0&limit=50`: 获取类源码。
- `GET /getClassStructure?class={fullName}`: 获取类成员（字段、方法）声明列表。
- `GET /getSmaliCode?class={fullName}`: 获取类的 Smali 字节码。
- `GET /getAllClass?offset=0&limit=50`: 分页列出 App 所有类名。

### 3. 追踪与分析 (Reverse Engineering)
- `GET /getXrefs?class={cls}&method={mth}`: 获取方法调用链。
- `GET /getXrefs?class={cls}&field={fld}`: 获取字段引用。
- `GET /getXrefs?class={cls}`: 获取类引用。
- **注**：返回格式为 `类名 | 方法签名`，单个查询限制 `2000` 条结果以防止 OOM。

### 4. 异步搜索任务 (Async Search)
- `GET /stringSearch?query={keyword}`: 全量源代码字符串搜索。
- `GET /cryptoScan`: 扫描常见的加密特征（AES, RSA, MD5 等）。
- `GET /getTaskStatus?id={taskId}`: 获取异步任务结果。

### 5. 重命名系统 (Refactoring)
- `POST /renameClass?oldName={old}&newName={new}`: 重构类名。
- `POST /renameMethod?class={cls}&method={old}&newName={new}`: 重构方法名。
- `POST /renameField / renameVariable`: 字段与变量重命名。
- `GET /exportMapping`: 导出当前 Session 的全量混淆映射表。

### 6. 资源分析 (Resources)
- `GET /getMainActivity`: 自动识别 `AndroidManifest.xml` 中的入口 Activity 并返回源码。
- `GET /getMainApplication`: 自动过滤三方 SDK，仅返回主包名下的业务类。
- `GET /getSource?name={fileName}`: 获取 XML/JSON/Assets 文本内容（支持 `startLine/endLine`）。
