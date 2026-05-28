# JADX NR-Plugin

JADX GUI plugin providing an HTTP API server for automated Android security analysis. Designed as the deterministic analysis backend for the NER-Agent (Go-based LLM agent).

---

## Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                        NER-Agent (Go)                              │
│         LLM reasoning + creative judgment + tool orchestration     │
└────────────────────────────────┬──────────────────────────────────┘
                                 │ HTTP (localhost:13997)
┌────────────────────────────────▼──────────────────────────────────┐
│                     JADX NR-Plugin (Java)                          │
│              Deterministic pre-computed analysis engine             │
│                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌──────────┐        │
│  │  /code   │  │ /search  │  │ /analyze  │  │ /system  │        │
│  │ 3 actions│  │ 3 actions│  │ 6 actions │  │ 5 actions│        │
│  └──────────┘  └──────────┘  └───────────┘  └──────────┘        │
│                                                                    │
│  ┌────────────────── Analysis Pipeline ──────────────────────┐    │
│  │ L0: Manifest    → L1: CodeIndex    → L2: CallGraph        │    │
│  │ L3: RuleEngine  → L4: SSATaint (on-demand)                │    │
│  └───────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────┘
```

**Design Principle**: The plugin does ALL deterministic, compute-intensive analysis work (decompilation, indexing, call graph construction, taint tracking, rule matching). The Go agent only performs creative reasoning and decision-making.

---

## Quick Start

### Install

```bash
# In jadx-gui or jadx-cli
jadx plugins --install "github:jadx-decompiler/jadx-nr-plugin"
```

### Ports

| Service | Port | Purpose |
|---|---|---|
| JADX Plugin | 13997 | This plugin's HTTP API |
| NER-Agent | 13998 | Go agent service |
| OneAPI | 13999 | LLM gateway |

### Verify

```bash
curl http://localhost:13997/system?action=status
```

---

## 5-Layer Analysis Pipeline

The plugin builds analysis indexes in a background pipeline after JADX loads an APK. Each layer adds capabilities:

| Layer | Name | Content | Time | Enables |
|---|---|---|---|---|
| **0** | Manifest | AndroidManifest parse + Entry points | <1s | `overview`, `entryPoints`, `component` |
| **1** | CodeIndex | All classes decompiled + String index + Library ID | 10-60s | `find`, `getClass`, `getMethod` |
| **2** | CallGraph | Invoke edges + Sinks/Sources + API endpoints + DI + Architecture | +0s | `callChain`, `dataFlow`, `attackSurface`, `findSinkSource`, `resolveDI` |
| **3** | RuleEngine | YAML security rules scan all classes | <2s | `scan` |
| **4** | SSATaint | On-demand SSA variable tracking | Per-call | `dataFlow` (deep mode) |

**Pipeline auto-starts**: L0 → L1+L2 (parallel build) → L3. L4 is triggered only when `dataFlow` is called.

---

## API Overview

### 4 Routes, 17 Actions

| Route | Actions | Purpose |
|---|---|---|
| `/code` | `getClass`, `getMethod`, `batchGetClass` | Retrieve decompiled code + structure + security annotations |
| `/search` | `find`, `scan`, `findSinkSource` | Search code, query rule findings, enumerate sinks/sources |
| `/analyze` | `component`, `callChain`, `dataFlow`, `entryPoints`, `attackSurface`, `resolveDI` | Deep analysis: call graphs, taint flows, attack surface |
| `/system` | `status`, `overview`, `rename`, `clearCache`, `reloadRules` | System management, APK overview, refactoring |

### Request Format

All requests are HTTP GET with query parameters:

```
GET /<route>?action=<action>&param1=value1&param2=value2
```

### Response Format

All responses are JSON. See `API_REFERENCE.md` for complete response schemas.

---

## Key Capabilities

### Call Graph (`/analyze?action=callChain`)
- Bidirectional traversal (up=callers, down=callees)
- Tree + flat-layer dual format
- Entry point reachability detection
- Max depth 6

### SSA Taint Analysis (`/analyze?action=dataFlow`)
- Tracks data flow from parameters to sinks via SSA variables
- Shallow (intra-method) and Deep (follows callees 1 level) modes
- Reports exact source→sink paths with risk levels

### Security Rule Engine (`/search?action=scan`)
- 55+ bundled YAML rules across 11 categories
- Match types: method_invoke, string_contains, class_inherit, annotation, composite (all/any/not)
- Hot-reloadable external rules
- 3-line context around each finding

### Attack Surface (`/analyze?action=attackSurface`)
- Entry point risk analysis (exported components + deep links)
- API endpoint extraction (Retrofit/OkHttp)
- Auth mechanism detection
- Sink distribution by category
- AI-generated priority list

### Sink/Source Annotation
- Auto-annotates all security-sensitive API calls
- Categories: crypto, webview, exec, sql, file, intent, network, dynamic_code, log
- Entry point reachability check per sink

### Universal Search (`/search?action=find`)
- 8 scopes: auto, class, method, code, string, url, secret, endpoint
- Auto mode cascades: class → method → code
- StringConstantIndex for O(1) string lookups
- Third-party libraries excluded by default

### Rename/Deobfuscation (`/system?action=rename`)
- Types: class, method, field, variable
- Variable rename uses SSA register/version for precision
- Export mapping table for Frida/Xposed script writing

---

## For Go Agent Developers

### Integration Patterns

1. **Always start with `status`** — check `health`, `decompiler_ready`, and layer states
2. **Handle 202 (building)** — retry after a few seconds when layers are still building
3. **Handle 503 (not ready)** — layer failed or not started
4. **Prefer `attackSurface` first** — single call gives comprehensive overview + priorities
5. **Use `component` for deep dives** — combines manifest + code + security in one call
6. **Use `callChain` direction=up** — trace from sink back to entry point
7. **Use `dataFlow` to confirm** — prove that tainted input actually reaches the sink

### Token Management

- `getClass` returns full code — can be large for complex classes
- `batchGetClass` returns structure only (no code) — safe for context windows
- `find` with `limit` parameter — control result size
- `scan` with `severity=high` — filter noise

### Key Response Fields for Knowledge Graph

| Response Field | Source | Use In Agent |
|---|---|---|
| `class_security_summary` | getClass, component | Node property in knowledge graph |
| `security_tags` | getClass, getMethod | Method-level annotations |
| `callers` | getMethod | Build call relationships |
| `chain_tree` | callChain | Path analysis |
| `flows` | dataFlow | Vulnerability confirmation |
| `entries[].risk_level` | entryPoints | Prioritization |
| `suggested_analysis_priorities` | attackSurface | Automated planning |

### Error Handling

```go
switch resp.StatusCode {
case 200:
    // Success - process response
case 202:
    // Layer building - wait and retry
    // Response has: {"status":"building", "layer":N, "progress":P}
case 400:
    // Bad params - fix request, don't retry same
case 404:
    // Target not found - try fuzzy search
case 503:
    // Not ready - check /system?action=status
case 500:
    // Internal error - try clearCache then retry once
}
```

---

## Development

### Build

```bash
./gradlew build
```

### Dependencies

- JADX 1.5.5 (`io.github.skylot`)
- Java 17+
- Gradle 8.x

### Project Structure

```
src/main/java/com/nine/ai/jadx/
├── server/
│   ├── PluginServer.java          # HTTP server + pipeline orchestration
│   └── handler/
│       ├── BaseHandler.java       # Route dispatch + layer gating
│       ├── CodeHandler.java       # /code (3 actions)
│       ├── SearchHandler.java     # /search (3 actions)
│       ├── AnalyzeHandler.java    # /analyze (6 actions)
│       └── SystemHandler.java     # /system (5 actions)
├── core/
│   ├── AnalysisLayers.java        # Layer state management
│   ├── CodeIndexManager.java      # L1+L2 parallel build orchestrator
│   ├── ManifestAnalyzer.java      # L0 manifest parsing
│   ├── EntryPointCollector.java   # L0 entry point aggregation
│   ├── CallGraph.java             # L2 bidirectional call graph
│   ├── SecurityAnnotator.java     # L2 sink/source annotation
│   ├── ApiEndpointIndex.java      # L2 Retrofit/OkHttp endpoint extraction
│   ├── DIBindingResolver.java     # L2 Dagger/Hilt DI resolution
│   ├── ArchitectureDetector.java  # L2 MVVM/MVP/Clean detection
│   ├── StringConstantIndex.java   # L1 string literal inverted index
│   ├── RuleEngine.java            # L3 YAML rule matcher
│   ├── RuleParser.java            # L3 YAML rule file parser
│   └── SSATaintAnalyzer.java      # L4 on-demand taint analysis
├── util/
│   ├── CodeUtil.java              # Class/method lookup helpers
│   ├── JadxUtil.java              # Decompiler access
│   └── HttpUtil.java              # HTTP response utilities
└── resources/
    └── rules/                     # Bundled YAML security rules (11 files)
        ├── crypto.yaml
        ├── ssl_tls.yaml
        ├── webview.yaml
        ├── ipc_security.yaml
        ├── dynamic_code.yaml
        ├── data_storage.yaml
        ├── data_leak.yaml
        ├── logging.yaml
        ├── network.yaml
        ├── hardcoded_secrets.yaml
        └── root_detection.yaml
```

---

## Documentation

- **[API_REFERENCE.md](API_REFERENCE.md)** — Complete API specification with all request/response schemas
- **[NERAgent docs/architecture-redesign.md](../NERAgent/docs/architecture-redesign.md)** — Original design specification
