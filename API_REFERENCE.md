# JADX-NR-Plugin API Reference — Agent Integration Guide

> **Base URL**: `http://localhost:13997`
> **Content-Type**: 所有响应均为 `application/json; charset=UTF-8`
> **CORS**: 已全局启用（`Access-Control-Allow-Origin: *`）
> **Agent 首要动作**: 启动后先调用 `systemStatus` 确认 `health.status == "UP"` 且 `decompiler_ready == true`

---

## 统一错误响应格式

所有错误响应均返回以下 JSON 结构，Agent 可通过检测 `"source":"jadx-plugin"` 字段确认错误来自插件（而非网络或其他服务），避免无意义重试：

```json
{
  "error": "错误描述信息",
  "source": "jadx-plugin"
}
```

| HTTP 状态码 | 含义 | Agent 应对策略 |
|---|---|---|
| `400` | 参数缺失或无效 | 检查请求参数，不要重试 |
| `404` | 目标类/方法/字段/资源不存在 | 换一个名称或确认目标是否存在，不要重试同一请求 |
| `405` | HTTP 方法不允许 | 换用正确的 HTTP 方法（通常为 GET） |
| `500` | 服务器内部错误 | 可尝试调用 `clearCache` 后重试一次，仍失败则上报 |
| `503` | 服务不可用/反编译器未就绪 | 等待片刻后调用 `systemStatus` 检查状态 |

---

## 1. 系统管理 — `/systemManager?action=xxx`

### 1.1 `systemStatus` — 获取系统状态 ★ 首先调用

```
GET /systemManager?action=systemStatus
```

**参数**: 无

**成功响应** (200 / 503):
```json
{
  "health": {
    "status": "UP",
    "decompiler_ready": true,
    "uptime_ms": 123456
  },
  "config": {
    "port": 13997,
    "cors_enabled": true,
    "version": "0.1.9-Agent-Core"
  },
  "resources": {
    "memory": {
      "max_mb": 4096,
      "used_mb": 1200,
      "free_mb": 800,
      "usage_percent": "29.30%"
    }
  },
  "available_apis": [
    {"path": "/codeInsight", "actions": ["getAllClasses", "getClassCode", "getClassStructure", "getClassSmali"]},
    {"path": "/resourceExplorer", "actions": ["getMainActivity", "getMainAppClasses", "getAllResourceNames", "getResourceFile"]},
    {"path": "/searchEngine", "actions": ["searchMethod", "searchClass", "searchString", "scanCrypto"]},
    {"path": "/getXrefs", "params": ["class", "method?", "field?", "offset?", "limit?"]},
    {"path": "/refactor", "actions": ["renameClass", "renameMethod", "renameField", "renameVariable", "exportMapping"]},
    {"path": "/systemManager", "actions": ["systemStatus", "clearCache", "taskStatus", "getApkOverview"]}
  ],
  "timestamp": 1713260000000
}
```

**关键判断**:
- `health.status == "UP"` 且 `health.decompiler_ready == true` → 可以开始工作
- `resources.memory.usage_percent > 85%` → 建议先调用 `clearCache`

---

### 1.2 `clearCache` — 清空缓存

```
GET /systemManager?action=clearCache
```

**参数**: 无

**成功响应** (200):
```json
{
  "status": "success",
  "message": "All caches (code and resources) cleared successfully."
}
```

**使用时机**: 大量 rename 操作后代码未更新、内存占用过高、服务端频繁报错时。

---

### 1.3 `taskStatus` — 查询异步任务状态

```
GET /systemManager?action=taskStatus&task_id=<task_id>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `task_id` | 是 | 由 `searchString` 或 `scanCrypto` 返回的任务 ID |

**成功响应** (200):

任务运行中:
```json
{
  "task_id": "a1b2c3d4",
  "type": "STRING_SEARCH",
  "status": "RUNNING",
  "timestamp": 1713260000000
}
```

任务成功:
```json
{
  "task_id": "a1b2c3d4",
  "type": "STRING_SEARCH",
  "status": "SUCCESS",
  "timestamp": 1713260005000,
  "result": ["com.example.MainActivity", "com.example.Utils"]
}
```

任务失败:
```json
{
  "task_id": "a1b2c3d4",
  "type": "STRING_SEARCH",
  "status": "FAILED",
  "timestamp": 1713260005000,
  "error": "Decompiler not available"
}
```

**轮询策略**: 每 2-3 秒查询一次，`status` 变为 `SUCCESS` 或 `FAILED` 时停止。任务 30 分钟后自动过期。

---

### 1.4 `getApkOverview` — 获取 APK 全貌 ★ 分析前调用

```
GET /systemManager?action=getApkOverview
```

**参数**: 无

**成功响应** (200):
```json
{
  "package_name": "com.example.app",
  "total_classes": 1234,
  "total_methods": 5678,
  "total_resources": 89,
  "components": {
    "activities": ["com.example.MainActivity", "com.example.SettingsActivity"],
    "services": ["com.example.MyService"],
    "receivers": ["com.example.BootReceiver"],
    "providers": ["com.example.DataProvider"]
  },
  "permissions": ["android.permission.INTERNET", "android.permission.READ_PHONE_STATE"],
  "min_sdk": 21,
  "target_sdk": 34
}
```

> 注: `min_sdk` 和 `target_sdk` 仅在 Manifest 中存在对应属性时返回。

---

## 2. 代码洞察 — `/codeInsight?action=xxx`

### 2.1 `getAllClasses` — 获取所有类列表（分页）

```
GET /codeInsight?action=getAllClasses&keyword=<可选>&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `keyword` | 否 | - | 类名模糊过滤（大小写不敏感） |
| `offset` | 否 | 0 | 分页起始偏移量 |
| `limit` | 否 | 50 | 每页条数（最大 500） |

**成功响应** (200):
```json
{
  "type": "class-list",
  "classes": [
    "com.example.app.MainActivity",
    "com.example.app.Utils",
    "com.example.app.network.ApiClient"
  ],
  "pagination": {
    "total": 1234,
    "offset": 0,
    "limit": 50,
    "count": 50,
    "has_more": true,
    "next_offset": 50
  }
}
```

**分页翻页**: 当 `has_more == true` 时，使用 `next_offset` 作为下一次请求的 `offset` 参数。

---

### 2.2 `getClassCode` — 获取类/方法的反编译 Java 代码

```
GET /codeInsight?action=getClassCode&code_name=<类名或方法名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `code_name` | 是 | 支持三种格式（按优先级匹配）: |

**`code_name` 支持的三种输入格式**:

1. **精确方法签名**: `com.example.Utils.encrypt(Ljava/lang/String;)V` → 返回该方法代码
2. **类名.方法名**: `com.example.Utils.encrypt` → 返回该方法代码
3. **完整类名**: `com.example.Utils` → 返回整个类代码

**成功响应 — 类代码** (200):
```json
{
  "type": "class",
  "class_name": "com.example.app.Utils",
  "code": "package com.example.app;\n\npublic class Utils {\n    ...\n}"
}
```

**成功响应 — 方法代码** (200):
```json
{
  "type": "method",
  "method_name": "com.example.app.Utils.encrypt",
  "code": "public String encrypt(String input) {\n    ...\n}"
}
```

---

### 2.3 `getClassStructure` — 获取类结构概览（字段、方法列表、继承关系）

```
GET /codeInsight?action=getClassStructure&class_name=<完整类名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `class_name` | 是 | 完整类名，如 `com.example.app.Utils` |

**成功响应** (200):
```json
{
  "class_name": "com.example.app.Utils",
  "super_class": "java.lang.Object",
  "implements": ["java.io.Serializable"],
  "fields": ["java.lang.String TAG", "int MAX_RETRY"],
  "methods": ["<init>()V", "encrypt(Ljava/lang/String;)Ljava/lang/String;", "decrypt(Ljava/lang/String;)Ljava/lang/String;"]
}
```

> `methods` 数组中的每一项是方法的 shortId 签名格式（方法名+参数类型+返回类型），可直接用于 `getClassCode` 的精确方法查询。

---

### 2.4 `getClassSmali` — 获取类的 Smali 字节码

```
GET /codeInsight?action=getClassSmali&class_name=<完整类名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `class_name` | 是 | 完整类名 |

**成功响应** (200):
```json
{
  "type": "smali",
  "class_name": "com.example.app.Utils",
  "code": ".class public Lcom/example/app/Utils;\n.super Ljava/lang/Object;\n..."
}
```

---

## 3. 资源浏览 — `/resourceExplorer?action=xxx`

### 3.1 `getMainActivity` — 获取主 Activity 代码

```
GET /resourceExplorer?action=getMainActivity
```

**参数**: 无

**成功响应** (200):
```json
{
  "type": "main_activity",
  "class_name": "com.example.app.MainActivity",
  "code": "package com.example.app;\n\npublic class MainActivity extends AppCompatActivity {\n    ...\n}"
}
```

---

### 3.2 `getMainAppClasses` — 获取主包下所有类（分页）

```
GET /resourceExplorer?action=getMainAppClasses&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `offset` | 否 | 0 | 分页偏移 |
| `limit` | 否 | 100 | 每页条数 |

**成功响应** (200):
```json
{
  "package": "com.example.app",
  "total": 456,
  "offset": 0,
  "limit": 100,
  "has_more": true,
  "classes": [
    "com.example.app.MainActivity",
    "com.example.app.Utils",
    "com.example.app.network.ApiClient"
  ]
}
```

---

### 3.3 `getAllResourceNames` — 获取所有资源文件名（分页）

```
GET /resourceExplorer?action=getAllResourceNames&keyword=<可选>&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `keyword` | 否 | - | 文件名模糊过滤（大小写不敏感） |
| `offset` | 否 | 0 | 分页偏移 |
| `limit` | 否 | 50 | 每页条数（最大 500） |

**成功响应** (200):
```json
{
  "type": "application-resources",
  "files": [
    "AndroidManifest.xml",
    "res/layout/activity_main.xml",
    "res/values/strings.xml"
  ],
  "pagination": {
    "total": 89,
    "offset": 0,
    "limit": 50,
    "count": 50,
    "has_more": true,
    "next_offset": 50
  }
}
```

---

### 3.4 `getResourceFile` — 获取资源文件内容

```
GET /resourceExplorer?action=getResourceFile&file_name=<资源文件名>&startLine=<可选>&endLine=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `file_name` | 是 | - | 资源文件名（从 `getAllResourceNames` 获取） |
| `startLine` | 否 | 1 | 起始行号 |
| `endLine` | 否 | 99999 | 结束行号 |

**成功响应** (200):
```json
{
  "type": "resource/text",
  "file": {
    "file_name": "AndroidManifest.xml",
    "content": "// Lines 1-50/120\n\n<?xml version=\"1.0\"...>"
  }
}
```

> 内容超过 200,000 字符时自动截断并提示，使用 `startLine` / `endLine` 分段读取。

---

## 4. 搜索引擎 — `/searchEngine?action=xxx`

### 4.1 `searchMethod` — 按方法名搜索（同步，分页）

```
GET /searchEngine?action=searchMethod&method_name=<方法名>&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `method_name` | 是 | - | 方法名关键词（大小写不敏感，模糊匹配） |
| `offset` | 否 | 0 | 分页偏移 |
| `limit` | 否 | 50 | 每页条数（最大 500） |

**成功响应** (200):
```json
{
  "type": "method-search-results",
  "methods": [
    "com.example.app.Utils | encrypt(Ljava/lang/String;)Ljava/lang/String;",
    "com.example.app.CryptoHelper | encryptAES(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
  ],
  "pagination": {
    "total": 15,
    "offset": 0,
    "limit": 50,
    "count": 15,
    "has_more": false
  }
}
```

> `methods` 数组格式为 `"完整类名 | 方法签名"`，可拆分出类名和方法签名。

---

### 4.2 `searchClass` — 按类名/代码搜索（同步，分页）

```
GET /searchEngine?action=searchClass&class_name=<搜索词>&package=<可选>&search_in=<可选>&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `class_name` | 是 | - | 搜索关键词（大小写不敏感） |
| `package` | 否 | - | 包名前缀过滤（如 `com.example`） |
| `search_in` | 否 | `class_name` | 搜索范围，可选: `class_name`, `code`, `class_name,code`（逗号分隔） |
| `offset` | 否 | 0 | 分页偏移 |
| `limit` | 否 | 50 | 每页条数（最大 500） |

**成功响应** (200):
```json
{
  "type": "class-list",
  "classes": [
    "com.example.app.CryptoUtils",
    "com.example.lib.CryptoHelper"
  ],
  "pagination": {
    "total": 2,
    "offset": 0,
    "limit": 50,
    "count": 2,
    "has_more": false
  }
}
```

---

### 4.3 `searchString` — 全局字符串搜索（异步）

```
GET /searchEngine?action=searchString&query=<搜索字符串>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `query` | 是 | 在所有类的反编译代码中搜索的字符串（精确包含匹配） |

**立即响应** (202):
```json
{
  "status": "ACCEPTED",
  "task_id": "a1b2c3d4",
  "message": "Search started"
}
```

缓存命中时:
```json
{
  "status": "ACCEPTED",
  "task_id": "e5f6g7h8",
  "message": "Result from cache"
}
```

**后续操作**: 使用返回的 `task_id` 调用 `taskStatus` 轮询结果。`result` 字段为包含该字符串的类名数组:
```json
["com.example.app.Config", "com.example.app.ApiClient"]
```

---

### 4.4 `scanCrypto` — 加密/安全特征扫描（异步）

```
GET /searchEngine?action=scanCrypto
```

**参数**: 无

**立即响应** (202):
```json
{
  "status": "ACCEPTED",
  "task_id": "x9y0z1w2"
}
```

**后续操作**: 轮询 `taskStatus`，`result` 字段为嫌疑类列表:
```json
[
  {"class": "com.example.app.CryptoUtils", "type": "CRYPTO_SENSITIVE", "hint": "Contains javax.crypto.Cipher"},
  {"class": "com.example.app.HashHelper", "type": "CRYPTO_SENSITIVE", "hint": "Contains MessageDigest"}
]
```

> 扫描特征: `javax.crypto.Cipher`, `SecretKeySpec`, `MessageDigest`, `getEncoded`

---

## 5. 交叉引用 — `/getXrefs`

```
GET /getXrefs?class=<类名>&method=<可选>&field=<可选>&offset=<可选>&limit=<可选>
```

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `class` | 是 | - | 完整类名 |
| `method` | 否 | - | 方法名（查该方法被谁调用） |
| `field` | 否 | - | 字段名（查该字段被谁使用） |
| `offset` | 否 | 0 | 分页偏移 |
| `limit` | 否 | 50 | 每页条数（最大 500） |

**优先级**: `field` > `method` > 类级别 xref

**成功响应 — 方法交叉引用** (200):
```json
{
  "type": "method-xrefs",
  "references": [
    "com.example.app.MainActivity | onCreate(Landroid/os/Bundle;)V",
    "com.example.app.Utils | init()V"
  ],
  "pagination": {
    "total": 5,
    "offset": 0,
    "limit": 50,
    "count": 5,
    "has_more": false
  }
}
```

**成功响应 — 类交叉引用** (200):
```json
{
  "type": "class-xrefs",
  "references": [
    "com.example.app.MainActivity",
    "com.example.app.SettingsActivity | loadConfig()V"
  ],
  "pagination": {
    "total": 100,
    "offset": 0,
    "limit": 50,
    "count": 50,
    "has_more": true,
    "next_offset": 50
  }
}
```

**溢出保护**: 结果超过 2000 条时截断并附加警告:
```json
{
  "pagination": {
    "is_overflow": true,
    "warning": "Result truncated to 2000 entries to prevent OOM."
  }
}
```

---

## 6. 重构 / 重命名 — `/refactor?action=xxx`

> **重要**: 所有 rename 操作执行后会自动清空缓存。Rename 改的是 JADX 显示名称，**不改变 APK 实际字节码**。编写 Frida/Xposed 脚本时必须用原始混淆名（通过 `exportMapping` 获取映射关系）。

### 6.1 `renameClass` — 重命名类

```
GET /refactor?action=renameClass&class_name=<原类名>&new_name=<新短名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `class_name` | 是 | 完整原类名，如 `p001.a.b` |
| `new_name` | 是 | 新的短类名（不含包名），如 `CryptoUtils` |

**成功响应** (200):
```json
{
  "status": "success",
  "message": "Successfully renamed class",
  "old_name": "b",
  "new_name": "CryptoUtils"
}
```

---

### 6.2 `renameMethod` — 重命名方法

```
GET /refactor?action=renameMethod&method_name=<完整方法路径>&new_name=<新方法名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `method_name` | 是 | 格式: `com.example.MyClass.oldMethodName` 或带签名 `com.example.MyClass.oldMethodName(Ljava/lang/String;)V` |
| `new_name` | 是 | 新方法名 |

**成功响应** (200):
```json
{
  "status": "success",
  "message": "Successfully renamed method",
  "class_name": "com.example.MyClass",
  "old_method_name": "a",
  "new_method_name": "encrypt"
}
```

---

### 6.3 `renameField` — 重命名字段

```
GET /refactor?action=renameField&class_name=<完整类名>&field_name=<原字段名>&new_name=<新字段名>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `class_name` | 是 | 完整类名 |
| `field_name` | 是 | 原字段名 |
| `new_name` | 是 | 新字段名（也接受参数名 `new_field_name`） |

**成功响应** (200):
```json
{
  "status": "success",
  "message": "Successfully renamed field",
  "class_name": "com.example.MyClass",
  "old_field_name": "a",
  "new_field_name": "secretKey"
}
```

---

### 6.4 `renameVariable` — 重命名局部变量

```
GET /refactor?action=renameVariable&class_name=<类名>&method_name=<方法名>&variable_name=<变量名>&new_name=<新名>&reg=<可选>&ssa=<可选>
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `class_name` | 是 | 完整类名 |
| `method_name` | 是 | 方法名（支持带签名格式） |
| `variable_name` | 是 | 原变量名 |
| `new_name` | 是 | 新变量名 |
| `reg` | 否 | 寄存器编号（消歧义用） |
| `ssa` | 否 | SSA 版本号（消歧义用） |

**成功响应** (200):
```json
{
  "status": "success",
  "message": "Successfully renamed local variable",
  "class_name": "com.example.MyClass",
  "method_name": "encrypt",
  "old_variable_name": "v0",
  "new_variable_name": "inputData"
}
```

---

### 6.5 `exportMapping` — 导出重命名映射表 ★ 写 Frida 脚本前必调

```
GET /refactor?action=exportMapping
```

**参数**: 无

**成功响应** (200):
```json
{
  "CryptoUtils": "b",
  "encrypt": "a",
  "secretKey": "f"
}
```

> 格式: `{"新名称": "原始混淆名"}` — 编写 `Java.use()` 时用 value（原始名）。

---

## 7. 分页规则（通用）

所有返回分页数据的接口共享以下规则:

| 约束 | 值 | 说明 |
|---|---|---|
| 默认 `limit` | 50 | 不传 limit 时的默认值 |
| 最大 `limit` | 500 | 超过 500 会被截断到 500，保护 Agent 上下文窗口 |
| `offset` 上限 | 1,000,000 | 防止异常偏移 |

**分页响应结构**:
```json
{
  "pagination": {
    "total": 1234,
    "offset": 0,
    "limit": 50,
    "count": 50,
    "has_more": true,
    "next_offset": 50
  }
}
```

**翻页方式**: `has_more == true` → 下次请求 `offset=next_offset`

---

## 8. Agent 推荐工作流

```
1. GET /systemManager?action=systemStatus
   → 确认 health.status=="UP" && decompiler_ready==true
   → 记录 available_apis 了解可用能力

2. GET /systemManager?action=getApkOverview
   → 获取包名、类数量、组件列表、权限，制定分析策略

3. GET /resourceExplorer?action=getMainActivity
   → 从入口 Activity 开始分析

4. GET /codeInsight?action=getClassCode&code_name=<目标类>
   → 逐步深入阅读关键类代码

5. GET /codeInsight?action=getClassStructure&class_name=<类名>
   → 快速了解类的字段、方法、继承关系

6. GET /searchEngine?action=searchMethod&method_name=encrypt
   → 搜索加密相关方法

7. GET /searchEngine?action=scanCrypto
   → 异步扫描加密特征
   → GET /systemManager?action=taskStatus&task_id=<id> 轮询结果

8. GET /getXrefs?class=<类名>&method=<方法名>
   → 追踪方法被哪些位置调用

9. GET /refactor?action=renameClass&class_name=p001.a.b&new_name=CryptoUtils
   → 重命名混淆类，提高可读性

10. GET /refactor?action=exportMapping
    → 导出映射表，确保 Frida 脚本使用原始混淆名
```
