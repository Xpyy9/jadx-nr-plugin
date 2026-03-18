
 ## JADX NR-Plugin

Using For Connect to NA-agent，reverse engine with AI

Install using location id: `github:jadx-decompiler:jadx-nr-plugin`

In jadx-cli:
```bash
  jadx plugins --install "github:jadx-decompiler:jadx-nr-plugin"
```

## API Useage

### Default Port
- JADX Plugin Port：13997
- Agent Port：13998
- One API Port：13999

### 获取类代码

```bash
# 1. 查询全限定名类
curl http://localhost:13997/classAsk?name=com.jd.jdsdk.security.AesCbcCrypto

# 2. 查询短类名（模糊匹配）
curl http://localhost:13997/classAsk?name=AesCbcCrypto

# 3. 查询类名.方法名（自动提取类名匹配）
curl http://localhost:13997/classAsk?name=com.jd.jdsdk.security.AesCbcCrypto.decrypt
```
### 获取资源文件
```bash
# 1. 查询res目录资源
curl http://localhost:13997/sourceAsk?name=res/layout/activity_main.xml

# 2. 查询assets目录资源
curl http://localhost:13997/sourceAsk?name=assets/config.json

# 3. 查询根目录资源（如AndroidManifest.xml）
curl http://localhost:13997/sourceAsk?name=AndroidManifest.xml

# 4. 兼容Windows路径分隔符
curl http://localhost:13997/sourceAsk?name=res\values\strings.xml
```
### 获取函数调用链
```bash
# 1. 查询全限定类名+方法名
curl http://localhost:13997/callAsk?name=com.jd.jdsdk.security.AesCbcCrypto.decrypt

# 2. 查询内部类方法（兼容$和.）
curl http://localhost:13997/callAsk?name=com.example.MyClass$InnerClass.doSomething
```
### 清空缓存
```bash
# curl POST请求（无参数）
curl -X POST http://localhost:13997/cache/clear
```
### 服务器状态监控
```bash
curl http://localhost:13997/status
```
### 服务器健康状态检查
```bash
curl http://localhost:13997/health
```



