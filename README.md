# Steno

个人知识库笔记 App —— Android / Kotlin / Jetpack Compose。

把零散的想法、截图、文件丢进来，让 AI 帮你整理成能检索的知识。
本地优先：数据全存在设备上（SQLite），不依赖任何云端账号。

> 项目原名 SNOTE，应用显示名已改为 **Steno**（速记）。

## 功能

- **笔记**：富文本编辑、标签、附件（图片 / 文件 / PDF）
- **对话**：接 CodeBuddy 网关，支持流式输出、图片识别、工具调用
- **Agent 工具**：读写笔记、联网搜索、写入长期记忆
- **记忆**：用户与 Agent 共同维护的全局记忆，可由用户管理、也可由 Agent 申请写入
- **文件解析**：txt / md / csv / docx / pptx / xlsx / PDF 文字层提取
- **主题**：底色（白 / 淡蓝 / 黑）× 强调色（5 种）
- **功能开关**：三层同时生效（工具层不注册 + 数据层卸载 + UI 隐藏）

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 36 / minSdk 26）。

```
gradlew.bat :app:assembleDebug
```

首次构建请在 `local.properties` 里指定 SDK 路径（该文件已被忽略，不会进仓库）：

```properties
sdk.dir=C:\\Android\\Sdk
```

国内网络可按需设置代理：

```
set HTTP_PROXY=http://127.0.0.1:7897
set HTTPS_PROXY=http://127.0.0.1:7897
```

## 架构约束（不可破）

1. **源数据**（doc / card / link / alias / tag / attachment / evidence）永不删除、永不压缩。
2. **派生物**（derived / fts_card / thumbs / ocr / vector）可删可重建。
3. **功能开关**必须三层同时生效：工具层不注册 + 数据层卸载 + UI 隐藏 —— 关掉的功能必须真的不可用，而不只是藏起来。
4. **花钱的派生物**（OCR / 向量）重建前必须弹预算门。

## 数据库

没有用 Room，手写 DDL + 版本迁移（`KbSchema.kt`）。

- 当前 `DB_VERSION = 7`
- 全文检索优先 FTS5，不可用时退回 FTS4（运行时探测）
- 加列一律走 `ensureColumns()` —— `CREATE TABLE IF NOT EXISTS` 对已存在的表是空操作，加不了列

## 依赖许可证

全部为宽松许可证，可安全用于开源项目：

| 依赖 | 许可证 | 用途 |
|---|---|---|
| OkHttp | Apache-2.0 | 网络 |
| PdfBox-Android | Apache-2.0 | PDF 文字层提取 |
| AndroidX / Compose | Apache-2.0 | UI 与运行时 |

**刻意排除 iText**（AGPL，会传染整个项目）。

## 许可证

[MIT](LICENSE)
