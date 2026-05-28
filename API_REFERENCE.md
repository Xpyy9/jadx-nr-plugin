# JADX-NR-Plugin API Reference

> **Base URL**: `http://localhost:13997`
> **Content-Type**: All responses are `application/json; charset=UTF-8`
> **CORS**: Globally enabled (`Access-Control-Allow-Origin: *`)
> **Architecture**: 4 routes, 17 actions, 5-layer analysis pipeline
> **Agent first action**: Call `/system?action=status` to verify readiness

---

## Unified Error Response

```json
{
  "error": "Error description",
  "source": "jadx-plugin"
}
```

| HTTP Status | Meaning | Agent Strategy |
|---|---|---|
| `400` | Invalid/missing params | Fix params, do not retry |
| `404` | Target not found | Try another name, do not retry same request |
| `500` | Internal error | Call `clearCache` then retry once |
| `503` | Layer not ready | Wait, then call `status` to check readiness |

### Layer-Building Response (202)

When an action requires a layer that is still building:

```json
{
  "status": "building",
  "layer": 1,
  "progress": 65,
  "message": "Index is being built. Retry in a few seconds."
}
```

---

## Analysis Layer Pipeline

The plugin runs a background pipeline after startup. Actions have layer dependencies — if the required layer isn't ready, you get a 202 response.

| Layer | Name | Content | Build Time |
|---|---|---|---|
| 0 | Manifest | AndroidManifest parsing + EntryPoints | <1s |
| 1 | CodeIndex | Decompiled code index + StringConstantIndex + Library ID | 10-60s |
| 2 | CallGraph | Call edges + SecurityAnnotator + ApiEndpoints + DI + Architecture | +0s (piggybacked on L1) |
| 3 | RuleEngine | YAML security rule scan | <2s (after L1+2) |
| 4 | SSATaint | On-demand SSA taint analysis | Per-request |

---

## Route 1: `/code` — Code Retrieval

### 1.1 `getClass` — Get full class data

```
GET /code?action=getClass&name=<class_name>
```

| Param | Required | Description |
|---|---|---|
| `name` | Yes | Full class name, e.g. `com.example.MyActivity` |

**Layer Dependency**: 0 (enriched with security data if L2+ ready)

**Response** (200):
```json
{
  "class_name": "com.example.MyActivity",
  "super_class": "android.app.Activity",
  "implements": ["android.view.View$OnClickListener"],
  "structure": {
    "fields": [
      {"name": "TAG", "type": "java.lang.String"}
    ],
    "methods": [
      {
        "name": "onCreate",
        "signature": "onCreate(Landroid/os/Bundle;)V",
        "access": "public",
        "security_tags": {
          "is_sink": true,
          "sink_categories": ["webview"],
          "rule_findings": [{"rule_id": "WEBVIEW_LOADURL", "severity": "high"}]
        }
      }
    ]
  },
  "methods": [...],
  "fields": [...],
  "code": "package com.example;\n\npublic class MyActivity extends Activity {\n    ...\n}",
  "class_security_summary": {
    "total_sinks": 3,
    "total_sources": 1,
    "sink_categories": ["webview", "log"],
    "source_categories": ["intent"],
    "rule_findings_count": 2,
    "highest_severity": "high"
  }
}
```

**Key fields for Go agent**:
- `class_name`: Canonical name
- `structure.methods[].security_tags`: Per-method sink/source/rule annotations
- `code`: Full decompiled source
- `class_security_summary`: Aggregated security posture

---

### 1.2 `getMethod` — Get single method with taint info

```
GET /code?action=getMethod&class=<class_name>&method=<method_name>
```

| Param | Required | Description |
|---|---|---|
| `class` | Yes | Full class name |
| `method` | Yes | Method name or shortId signature |

**Layer Dependency**: 0 (enriched with L2 callers + L4 taint if ready)

**Response** (200):
```json
{
  "class_name": "com.example.Utils",
  "method_name": "encrypt",
  "method_signature": "encrypt(Ljava/lang/String;)Ljava/lang/String;",
  "signature": "encrypt(Ljava/lang/String;)Ljava/lang/String;",
  "code": "public String encrypt(String input) {\n    ...\n}",
  "security_tags": {
    "is_sink": true,
    "sink_categories": ["crypto"],
    "has_tainted_params": true,
    "taint_summary": "参数 [0] 可能来自外部输入; 2 条污点路径"
  },
  "callers": [
    {"class_name": "com.example.LoginActivity", "method_name": "doLogin", "method_signature": "doLogin"}
  ],
  "caller_count": 1
}
```

**Key fields for Go agent**:
- `security_tags.has_tainted_params`: Whether method receives tainted input
- `security_tags.taint_summary`: Human-readable taint description
- `callers`: Who calls this method (from CallGraph)
- `code`: Decompiled method body

---

### 1.3 `batchGetClass` — Batch class structure (no code body)

```
GET /code?action=batchGetClass&names=<comma_separated_names>
```

| Param | Required | Description |
|---|---|---|
| `names` | Yes | Comma-separated class names, **max 5** |

**Response** (200):
```json
{
  "type": "batch-class-structure",
  "results": [
    {
      "class_name": "com.example.A",
      "methods": [{"name": "foo", "access": "public", "security_tags": {...}}],
      "method_count": 5,
      "field_count": 2,
      "class_security_summary": {...}
    },
    {
      "class_name": "com.example.B",
      "error": "not found"
    }
  ],
  "count": 2
}
```

> Note: `batchGetClass` returns structure only (no `code` field) to prevent token explosion.

---

## Route 2: `/search` — Search & Scan

### 2.1 `find` — Universal search

```
GET /search?action=find&query=<search_term>&scope=<scope>&offset=<n>&limit=<n>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `query` | Yes | - | Search keyword |
| `scope` | No | `auto` | One of: `auto`, `class`, `method`, `code`, `string`, `url`, `secret`, `endpoint` |
| `offset` | No | 0 | Pagination offset |
| `limit` | No | 50 | Results per page |

**Layer Dependency**: 1

**Scope behavior**:
| Scope | Strategy | What it searches |
|---|---|---|
| `auto` | Cascade: class→method→code | First non-empty result wins |
| `class` | Class name match | Case-insensitive substring on class names |
| `method` | Method declaration match | Regex-based method name search |
| `code` | Source code content | Full-text substring search in decompiled code |
| `string` | StringConstantIndex | Inverted index of string literals |
| `url` | URL extraction | Finds `http://` / `https://` strings |
| `secret` | Secret detection | Finds high-entropy base64-like strings (16+ chars) |
| `endpoint` | API endpoint | Finds Retrofit annotations `@GET/@POST/...` |

**Response** (200):
```json
{
  "type": "search-results",
  "query": "encrypt",
  "scope": "auto",
  "strategy": "method_name",
  "results": [
    {
      "class_name": "com.example.CryptoUtils",
      "match_type": "method_name",
      "is_third_party": false,
      "security_summary": {...}
    }
  ],
  "total": 3,
  "offset": 0,
  "limit": 50,
  "has_more": false
}
```

**Special scope results**:

`scope=url`:
```json
{"class_name": "...", "match_type": "url", "urls": ["https://api.example.com/v1/users"]}
```

`scope=secret`:
```json
{"class_name": "...", "match_type": "possible_secret", "values": ["aGVsbG8gd29ybGQ..."]}
```

`scope=endpoint`:
```json
{"class_name": "...", "match_type": "api_endpoint", "endpoints": [{"method": "POST", "path": "/api/login"}]}
```

---

### 2.2 `scan` — Security rule scan results

```
GET /search?action=scan&category=<cat>&severity=<sev>&limit=<n>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `category` | No | `all` | Filter: `crypto`, `ssl_tls`, `webview`, `ipc_security`, `dynamic_code`, `data_storage`, `data_leak`, `logging`, `network`, `hardcoded_secrets`, `root_detection`, or `all` |
| `severity` | No | `info` | Minimum severity: `info`, `medium`, `high`, `critical` |
| `limit` | No | 100 | Max findings returned |

**Layer Dependency**: 3

**Response** (200):
```json
{
  "type": "security-scan",
  "category": "all",
  "min_severity": "high",
  "summary": {
    "total_findings": 42,
    "critical": 2,
    "high": 8,
    "medium": 20,
    "info": 12,
    "rules_loaded": 55,
    "by_category": {"crypto": 5, "webview": 3, "data_leak": 10, ...}
  },
  "findings": [
    {
      "rule_id": "WEAK_CRYPTO_AES_ECB",
      "severity": "high",
      "category": "crypto",
      "class_name": "com.example.CryptoUtils",
      "line_number": 42,
      "description": "AES/ECB mode detected - vulnerable to pattern analysis",
      "context": ["41:     Cipher c = Cipher.getInstance(", "42: >>> \"AES/ECB/PKCS5Padding\"", "43:     );"],
      "tags": ["owasp-m5", "cwe-327"],
      "match_type": "method_invoke",
      "confidence": "high",
      "remediation": "Use AES/GCM/NoPadding instead"
    }
  ],
  "total_findings": 10
}
```

---

### 2.3 `findSinkSource` — Sink/Source enumeration

```
GET /search?action=findSinkSource&type=<type>&category=<cat>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `type` | No | `both` | `sink`, `source`, or `both` |
| `category` | No | all | Filter by sink/source category: `crypto`, `webview`, `exec`, `sql`, `file`, `intent`, `network`, `log`, `deeplink`, etc. |

**Layer Dependency**: 2

**Response** (200):
```json
{
  "type": "sink-source-list",
  "filter_type": "both",
  "filter_category": null,
  "results": [
    {
      "method_key": "com.example.Utils#loadUrl",
      "class_name": "com.example.Utils",
      "method_name": "loadUrl",
      "type": "sink",
      "category": "webview",
      "caller_count": 3,
      "is_reachable_from_entry": true
    }
  ],
  "total": 15,
  "sinks_total": 42,
  "sources_total": 18
}
```

---

## Route 3: `/analyze` — Deep Analysis

### 3.1 `component` — Single component deep analysis

```
GET /analyze?action=component&name=<class_name>
```

| Param | Required | Description |
|---|---|---|
| `name` | Yes | Full class name of the Android component |

**Layer Dependency**: 0 (enriched with L2/L3 if ready)

**Response** (200):
```json
{
  "component_name": "com.example.DeepLinkActivity",
  "type": "activity",
  "manifest": {
    "exported": true,
    "has_permission": false,
    "intent_filters": [
      {"actions": ["android.intent.action.VIEW"], "categories": ["android.intent.category.DEFAULT"], "data": [{"scheme": "myapp", "host": "open"}]}
    ],
    "security_note": "exported 且无 permission 保护，接受 deep link 输入"
  },
  "structure": {
    "super_class": "android.app.Activity",
    "implements": [],
    "methods": [
      {"name": "onCreate", "signature": "onCreate(Landroid/os/Bundle;)V", "security_tags": {...}}
    ],
    "fields": [...]
  },
  "code": "package com.example;\n...",
  "class_security_summary": {
    "total_sinks": 2,
    "sink_categories": ["webview"],
    "source_categories": ["deeplink", "intent"],
    "key_finding": "Deep link 输入直接流入 WebView.loadUrl(), 无过滤"
  }
}
```

---

### 3.2 `callChain` — Call chain tracing

```
GET /analyze?action=callChain&class=<class>&method=<method>&direction=<dir>&depth=<n>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `class` | Yes | - | Full class name |
| `method` | Yes | - | Method name |
| `direction` | No | `up` | `up` (who calls me) or `down` (who I call). `callers` is alias for `up` |
| `depth` | No | 3 | Max trace depth (capped at 6) |

**Layer Dependency**: 2

**Response** (200):
```json
{
  "target": "com.example.Utils#encrypt",
  "direction": "up",
  "depth": 3,
  "chain": [
    {
      "depth": 1,
      "methods": [
        {"class": "com.example.LoginVM", "method": "doLogin"}
      ]
    },
    {
      "depth": 2,
      "methods": [
        {"class": "com.example.LoginActivity", "method": "onSubmit"}
      ]
    }
  ],
  "chain_tree": {
    "method": "com.example.Utils#encrypt",
    "children": [
      {
        "method": "com.example.LoginVM#doLogin",
        "children": [
          {"method": "com.example.LoginActivity#onSubmit", "children": [], "is_entry_point": true}
        ]
      }
    ]
  },
  "paths_to_entry": 1,
  "total_paths": 2,
  "total_nodes": 3
}
```

**Key fields for Go agent**:
- `chain`: Flat layer format, easy to iterate
- `chain_tree`: Nested tree format for LLM readability
- `paths_to_entry`: How many paths reach an Android entry point (exported component lifecycle)

---

### 3.3 `dataFlow` — SSA taint analysis

```
GET /analyze?action=dataFlow&class=<class>&method=<method>&depth_mode=<mode>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `class` | Yes | - | Full class name |
| `method` | Yes | - | Method name |
| `depth_mode` | No | `shallow` | `shallow` (intra-method) or `deep` (follows callees 1 level) |

**Layer Dependency**: 2 (Layer 4 is triggered on-demand)

**Response** (200):
```json
{
  "class_name": "com.example.Utils",
  "method_name": "processInput",
  "depth_mode": "shallow",
  "tainted_params": [0],
  "flows": [
    {
      "source": {"type": "param", "index": 0, "name": "input"},
      "sink": {"type": "invoke", "target": "android.webkit.WebView#loadUrl", "category": "webview"},
      "path": ["param[0]", "v2 = input.toString()", "webView.loadUrl(v2)"],
      "risk": "high"
    }
  ],
  "summary": {
    "total_flows": 1,
    "high_risk_flows": 1,
    "sink_categories": ["webview"],
    "recommendation": "Validate and sanitize input before passing to WebView"
  }
}
```

---

### 3.4 `entryPoints` — Entry point aggregation

```
GET /analyze?action=entryPoints&filter=<filter>
```

| Param | Required | Default | Description |
|---|---|---|---|
| `filter` | No | `all` | `all`, `activity`, `service`, `receiver`, `provider`, `deeplink` |

**Layer Dependency**: 0 (enriched with L2 if ready)

**Response** (200):
```json
{
  "type": "entry-points",
  "filter": "all",
  "entries": [
    {
      "class_name": "com.example.DeepLinkActivity",
      "name": "com.example.DeepLinkActivity",
      "component_type": "activity",
      "type": "activity",
      "exported": true,
      "has_permission": false,
      "has_intent_filter": true,
      "deep_links": [{"scheme": "myapp", "host": "open", "path": "/target"}],
      "contains_sinks": ["webview"],
      "source_categories": ["deeplink", "intent"],
      "risk_level": "critical"
    }
  ],
  "summary": {
    "total_entries": 12,
    "unprotected": 5,
    "with_deeplinks": 3,
    "highest_risk": "critical"
  }
}
```

**Risk level computation**:
- `critical`: Provider without permission, OR exec/sql sinks, OR deeplink→webview
- `high`: Unprotected component with sink calls
- `medium`: Unprotected without known sinks
- `low`: Protected with permission

---

### 3.5 `attackSurface` — Full attack surface overview

```
GET /analyze?action=attackSurface
```

**Layer Dependency**: 2 (L3 optional, enriches rule_scan_summary)

**Response** (200):
```json
{
  "type": "attack-surface",
  "entry_points": {
    "total": 12,
    "unprotected": 5,
    "by_type": {"activity": 6, "service": 3, "receiver": 2, "provider": 1},
    "deep_links": ["myapp://open", "https://example.com"],
    "top_risk": [
      {"name": "DeepLinkActivity", "risk": "critical", "reason": "deep link → webview, no filter"},
      {"name": "DataProvider", "risk": "critical", "reason": "exported provider, no permission"}
    ]
  },
  "api_endpoints": {
    "total": 15,
    "by_method": {"GET": 8, "POST": 5, "PUT": 2},
    "base_urls": ["https://api.example.com/v1"],
    "auth_required_count": 12
  },
  "auth_mechanisms": {
    "interceptor_classes": ["com.example.AuthInterceptor"],
    "token_type": "Bearer",
    "storage_method": "SharedPreferences"
  },
  "sink_distribution": {
    "categories": {
      "crypto": {"count": 8, "classes": 3, "highest_severity": "high"},
      "webview": {"count": 4, "classes": 2, "highest_severity": "critical"},
      "exec": {"count": 0},
      "sql": {"count": 2, "classes": 1, "highest_severity": "medium"},
      "file": {"count": 5, "classes": 3, "highest_severity": "medium"},
      "network": {"count": 12, "classes": 6, "highest_severity": "low"},
      "log": {"count": 20, "classes": 10, "highest_severity": "info"}
    }
  },
  "app_architecture": {
    "primary_pattern": "MVVM",
    "confidence": 0.85,
    "di_framework": "Hilt",
    "network_library": "Retrofit+OkHttp"
  },
  "rule_scan_summary": {
    "total_findings": 42,
    "critical": 2,
    "high": 8,
    "medium": 20,
    "info": 12,
    "rules_loaded": 55,
    "by_category": {...},
    "top_findings": [...]
  },
  "suggested_analysis_priorities": [
    "1. DeepLinkActivity: deep link → webview, no filter",
    "2. DataProvider: exported provider, no permission",
    "3. Command injection analysis (exec sinks found)"
  ]
}
```

---

### 3.6 `resolveDI` — Dependency Injection resolution

```
GET /analyze?action=resolveDI&name=<interface_name>
```

| Param | Required | Description |
|---|---|---|
| `name` | Yes | Interface or abstract class name to resolve |

**Layer Dependency**: 2

**Response** (200):
```json
{
  "interface": "com.example.Repository",
  "implementations": ["com.example.RepositoryImpl", "com.example.MockRepository"],
  "implementation_count": 2,
  "binding_details": [
    {
      "class": "com.example.RepositoryImpl",
      "constructor_dependencies": ["com.example.ApiService", "com.example.AppDatabase"]
    }
  ],
  "modules": ["com.example.di.AppModule", "com.example.di.NetworkModule"]
}
```

---

## Route 4: `/system` — System Management

### 4.1 `status` — System health & layer status (CALL FIRST)

```
GET /system?action=status
```

**Layer Dependency**: None

**Response** (200):
```json
{
  "health": "UP",
  "decompiler_ready": true,
  "memory": {
    "used_mb": 1200,
    "max_mb": 4096,
    "usage_percent": 29
  },
  "layers": {
    "layer_0_manifest": {"state": "READY", "progress": 100},
    "layer_1_code_index": {"state": "READY", "progress": 100, "classes_indexed": 3456, "strings_indexed": 12000},
    "layer_2_call_graph": {"state": "READY", "progress": 100, "edges": 8900, "sinks": 42, "sources": 18, "api_endpoints": 15, "di_bindings": 8},
    "layer_3_rule_engine": {"state": "READY", "progress": 100, "rules_loaded": 55, "findings": 42},
    "layer_4_ssa_taint": {"state": "ON_DEMAND", "cached_methods": 0}
  },
  "app_architecture": {...},
  "libraries_detected": ["Retrofit", "OkHttp", "Gson", "Hilt", "Glide"],
  "third_party_classes": 2100,
  "app_classes": 1356,
  "uptime_seconds": 120
}
```

**Agent readiness check**:
```
health == "UP" AND decompiler_ready == true → Ready to work
layers.layer_0_manifest.state == "READY" → Can call /analyze?action=entryPoints, component
layers.layer_1_code_index.state == "READY" → Can call /search?action=find, /code?action=getClass
layers.layer_2_call_graph.state == "READY" → Can call /analyze?action=callChain, dataFlow, attackSurface
layers.layer_3_rule_engine.state == "READY" → Can call /search?action=scan
```

---

### 4.2 `overview` — APK overview (cached)

```
GET /system?action=overview
```

**Layer Dependency**: 0

**Response** (200):
```json
{
  "package_name": "com.example.app",
  "min_sdk": 21,
  "target_sdk": 34,
  "application": {
    "debuggable": false,
    "allowBackup": true,
    "networkSecurityConfig": "network_security_config.xml",
    "usesCleartextTraffic": false
  },
  "components_summary": {
    "activities": 12,
    "services": 3,
    "receivers": 5,
    "providers": 2,
    "total": 22,
    "exported": 5
  },
  "permissions": ["android.permission.INTERNET", "android.permission.CAMERA", ...],
  "security_findings": [
    {"type": "allowBackup", "severity": "medium", "detail": "allowBackup=true enables data extraction"},
    {"type": "exported_no_permission", "severity": "high", "detail": "3 components exported without permission"}
  ],
  "deep_links": [
    {"scheme": "myapp", "host": "open", "path": "/target"}
  ],
  "total_classes": 3456,
  "total_methods": 18900,
  "packages": {"com.example": 1200, "androidx": 800, "com.google": 400, ...},
  "libraries_detected": ["Retrofit", "OkHttp", "Gson", "Hilt"]
}
```

> Response is cached after first call. Use `clearCache` to invalidate.

---

### 4.3 `rename` — Rename class/method/field/variable

```
GET /system?action=rename&type=<type>&target=<target>&new_name=<new_name>
```

| Param | Required | Description |
|---|---|---|
| `type` | Yes | `class`, `method`, `field`, `variable`, or `export` |
| `target` | Yes* | See format below (*not needed for `export`) |
| `new_name` | Yes* | New name (*not needed for `export`) |
| `reg` | No | Register number (variable disambiguation) |
| `ssa` | No | SSA version (variable disambiguation) |

**Target format by type**:
| Type | Target Format | Example |
|---|---|---|
| `class` | Full class name | `com.example.p001.a` |
| `method` | `ClassName#methodName` | `com.example.Utils#a` |
| `field` | `ClassName#fieldName` | `com.example.Utils#b` |
| `variable` | `ClassName#methodName#varName` | `com.example.Utils#encrypt#v0` |
| `export` | (not needed) | - |

**Variable disambiguation**: When multiple SSA variables share the same name, use `reg` and/or `ssa` params:
- `reg=5` — match by register number only
- `reg=5&ssa=2` — match by register + SSA version (most precise)
- Neither — match by variable name (first match)

**Rename response** (200):
```json
{
  "success": true,
  "type": "variable",
  "target": "com.example.Utils#encrypt#v0",
  "new_name": "inputData"
}
```

**Export response** (200):
```json
{
  "type": "rename_export",
  "mappings": {"CryptoUtils": "a", "encrypt": "b", "secretKey": "f0"},
  "total": 3
}
```

> Mappings format: `{"newName": "originalObfuscatedName"}`. Use original names for Frida/Xposed hooks.

---

### 4.4 `clearCache` — Invalidate all caches

```
GET /system?action=clearCache
```

**Response** (200):
```json
{
  "success": true,
  "message": "All caches cleared. Layers will rebuild on next request."
}
```

> Clears: CodeIndex, CallGraph, SecurityAnnotator, RuleEngine findings, ManifestAnalyzer cache, overview cache. Layers will auto-rebuild on next dependent request.

---

### 4.5 `reloadRules` — Hot-reload security rules

```
GET /system?action=reloadRules&path=<optional_rules_dir>
```

| Param | Required | Description |
|---|---|---|
| `path` | No | External rules directory path. If omitted, reloads bundled rules. |

**Response** (200):
```json
{
  "success": true,
  "rules_loaded": 55,
  "findings_after_rescan": 42
}
```

---

## Agent Recommended Workflow

```
Phase 1: Initialization
─────────────────────────────────────────────────────
1. GET /system?action=status
   → Verify health=="UP" && decompiler_ready==true
   → Check layer states for readiness
   → Note memory usage

2. GET /system?action=overview
   → Get package_name, permissions, components, architecture
   → Note security_findings for immediate risks
   → Identify deep_links for attack surface

Phase 2: Attack Surface Assessment
─────────────────────────────────────────────────────
3. GET /analyze?action=attackSurface
   → Full attack surface with entry_points, api_endpoints, sinks
   → Use suggested_analysis_priorities to guide next steps

4. GET /analyze?action=entryPoints&filter=all
   → Prioritize: risk_level==critical first
   → Note contains_sinks + source_categories

Phase 3: Deep Dive (per priority component)
─────────────────────────────────────────────────────
5. GET /analyze?action=component&name=<target>
   → Get manifest + code + security_summary in one call
   → Read key_finding for quick assessment

6. GET /analyze?action=callChain&class=<x>&method=<y>&direction=up
   → Trace who calls the vulnerable method
   → Check paths_to_entry to see if reachable from exported components

7. GET /analyze?action=dataFlow&class=<x>&method=<y>&depth_mode=deep
   → SSA taint analysis: does user input reach this sink?
   → Read flows[].path for the exact data flow

Phase 4: Targeted Searches
─────────────────────────────────────────────────────
8. GET /search?action=find&query=encrypt&scope=method
   → Find crypto-related methods

9. GET /search?action=find&query=api.example.com&scope=url
   → Find hardcoded URLs

10. GET /search?action=scan&severity=high
    → Get all high+ severity rule findings

11. GET /search?action=findSinkSource&type=sink&category=exec
    → Find command execution sinks

Phase 5: Deobfuscation (as needed)
─────────────────────────────────────────────────────
12. GET /system?action=rename&type=class&target=com.a.b.c&new_name=CryptoManager
    → Rename obfuscated classes for readability

13. GET /system?action=rename&type=export
    → Export mapping before writing Frida scripts
```

---

## Summary Table

| Route | Action | Layer | Purpose |
|---|---|---|---|
| `/code` | `getClass` | 0+ | Full class: structure + code + security |
| `/code` | `getMethod` | 0+ | Single method: code + taint + callers |
| `/code` | `batchGetClass` | 0+ | Batch structure (no code body) |
| `/search` | `find` | 1 | Universal search (8 scopes) |
| `/search` | `scan` | 3 | Security rule findings |
| `/search` | `findSinkSource` | 2 | Enumerate sinks and sources |
| `/analyze` | `component` | 0+ | Deep single-component analysis |
| `/analyze` | `callChain` | 2 | Call graph traversal (up/down) |
| `/analyze` | `dataFlow` | 2 | SSA taint analysis |
| `/analyze` | `entryPoints` | 0+ | Exported component listing |
| `/analyze` | `attackSurface` | 2+ | Full attack surface overview |
| `/analyze` | `resolveDI` | 2 | DI binding resolution |
| `/system` | `status` | - | Health + layer status |
| `/system` | `overview` | 0 | APK metadata overview |
| `/system` | `rename` | 0 | Rename/export mappings |
| `/system` | `clearCache` | - | Invalidate all caches |
| `/system` | `reloadRules` | - | Hot-reload YAML rules |
