# 开发与构建

## 环境

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.3.20 |
| 构建 | AGP 9.0.1 / Gradle 9.1.0 |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26 |
| JDK | 17 |
| 关键依赖 | Shizuku API 13.1.5、WorkManager 2.12.0、NanoHTTPD 2.3.1、Compose Material 3 |

依赖版本集中在 `gradle/libs.versions.toml`。

## 命令

```bash
./gradlew :app:assembleDebug      # 构建调试包
./gradlew :app:lintDebug          # 静态检查
./gradlew :app:assembleRelease    # 构建发布包（需自行配置签名）
```

构建产物位于 `app/build/outputs/apk/`。

## 调试版与发布版

- 调试包带 `DEBUGGABLE` 标记，支持 USB CLI 的 `run-as` 访问规则文件
- 发布包使用独立签名，不支持 `run-as`；局域网网关功能不受影响
- 发布版开启 R8 与资源压缩；签名信息从 `local.properties` 读取，密钥文件不在仓库内

发布构建所需配置（写入仓库外的密钥文件与本机 `local.properties`）：

```properties
latch.storeFile=C:/path/to/latch.jks
latch.storePassword=******
latch.keyAlias=latch
latch.keyPassword=******
```

## 目录结构

```
app/src/main/java/dev/latch/rule/
├─ core/          领域模型与错误分类
├─ privilege/     特权通道
├─ engine/        命令、解析、应用、校验
├─ domain/        规则状态中枢
├─ data/          持久化与编解码
├─ gateway/       局域网网关
├─ ui/            Compose 界面
├─ work/          后台任务
├─ receiver/      开机广播
└─ notify/        通知
tools/            adb CLI 与局域网 CLI
docs/             项目文档
```

## 版本约定

- 应用版本号在 `app/build.gradle.kts` 中维护
- 规则 JSON 格式带 `version` 字段，新增能力时保持向后兼容
