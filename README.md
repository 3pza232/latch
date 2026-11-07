# Latch · 门闩

Latch 是一个基于 [Shizuku](https://shizuku.rikka.app/) 的本地 Android 规则管理器。
它把对应用的系统级限制（AppOps、权限、启停、强制停止）表达为可编辑的规则，
执行后回读系统状态，不一致时标记为"漂移"，并支持通过局域网远程管理。

- 应用 ID：`dev.latch.rule`
- 最低系统：Android 8.0（API 26）
- 权限方式：Shizuku，无需 root
- 网络行为：默认不监听；仅在手动开启"局域网连接"后本地监听

## 功能

- 规则组与规则的两级管理，规则可单独启停
- 能力：AppOps 模式、授予/撤销权限、强制停止、启用/停用应用
- 应用规则后逐条回读校验，区分"已生效 / 已漂移 / 失败 / 待应用"
- 校验结果持久化，重新打开应用仍能看到上次状态
- 打开应用自动校验、开机自检、周期性后台校验、漂移通知
- 规则 JSON 导入/导出，覆盖安装保留
- 局域网连接：配对码 + token，REST API 与自描述文档，供脚本或 AI 管理
- 应用不内置任何规则，首次安装为空

## 快速开始

```bash
./gradlew :app:assembleDebug          # 调试包，支持 USB CLI
./gradlew :app:assembleRelease        # 发布包，需本机配置签名
```

安装后启动 Latch：

1. 手机上启动 Shizuku，在 Latch 首页点「授权」
2. 新建规则组，进入分组后添加规则（选择应用与能力）
3. 点「应用」下发，之后可随时校验
4. 需要远程管理时，设置页开启「局域网连接」，把地址、端口、配对码交给客户端

调试包的 USB CLI（`tools/latch-agent.ps1`）依赖 `run-as`，发布包不支持；
局域网连接与 `tools/latch-remote.ps1` 在两种包上都可用。

## 文档

- [架构设计](docs/architecture.md)
- [规则与数据格式](docs/rules.md)
- [局域网接口与 CLI](docs/agent.md)
- [开发与构建](docs/development.md)

## 技术栈

Kotlin 2.3 / Jetpack Compose（Material 3）/ AGP 9 / Shizuku API 13 /
WorkManager / NanoHTTPD

---

by pza
