# 修改细节 (changes.md)

本项目对 `McpClient.kt` 中因 OkHttp 版本升级导致的 `HttpUrl.parse` 编译错误进行了修复。以下是详细的修改细节：

## 1. 目标文件
- 文件路径：`app/src/main/java/com/loyea/mcp/McpClient.kt`

## 2. 修复细节
为了兼容最新的 OkHttp 版本并解决 `"Using 'parse(String): HttpUrl?' is an error. moved to extension function"` 编译错误，文件已进行了以下调整：

### 2.1 引入 OkHttp 扩展函数导入
在文件头部（第12行）引入了扩展函数：
```kotlin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
```

### 2.2 替换 `HttpUrl.parse` 逻辑
在连接 MCP 服务并解析 SSE 链接与 Endpoint 的过程中，将原有的 `HttpUrl.parse` 方法替换为 `toHttpUrlOrNull()` 扩展方法：
- **SSE URL 解析 (第 104 行):**
  ```kotlin
  val parsedSseUrl = config.sseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid SSE URL: ${config.sseUrl}")
  ```
- **Endpoint 链接解析 (第 106 行):**
  ```kotlin
  val parsedEndpoint = endpoint.toHttpUrlOrNull() ?: throw IOException("Invalid endpoint URL: $endpoint")
  ```

## 3. 影响评估
- **编译影响：** 该修复符合 OkHttp 官方对于废弃 `HttpUrl.parse` 的指导方案，使用 `String.toHttpUrlOrNull()` 能够保证 Kotlin 编译器顺利解析并编译成功。
- **功能影响：** 扩展函数 `toHttpUrlOrNull()` 底层行为与原 `HttpUrl.parse(...)` 一致，不会对 MCP Client 的连接及 SSRF 验证逻辑产生任何行为改变。
