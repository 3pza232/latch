# 架构设计

## 分层

```
core/model     领域模型：Rule / Capability / RuleGroup / RuleState
core/result    错误分类：LatchError
privilege      特权通道：PrivilegeBroker 与 Shizuku 实现
engine         命令映射、输出解析、应用与校验
domain         RuleCoordinator：规则状态中枢
data           规则 / 结果 / 设置存储，JSON 编解码，已安装应用
gateway        局域网网关：配对、鉴权、REST、前台服务
ui             Compose 界面、ViewModel、组件
work           后台校验 Worker 与调度
receiver       开机自检
notify         漂移通知
```

依赖方向单向：`ui → domain → engine → privilege`。
UI 不拼接命令，engine 不直接持有特权，只有 `CapabilityCommands` 生成命令。

## 核心组件

| 组件 | 职责 |
|---|---|
| `PrivilegeBroker` | 执行 shell 命令并返回 stdout/stderr/exitCode |
| `ShizukuPrivilegeBroker` | 通过 Shizuku 获取远程进程；反射调用隔离在 `ShizukuShell` |
| `CapabilityCommands` | 能力到命令的唯一映射，参数先做正则校验 |
| `RuleEngine` | 应用与回读校验，产出 `RuleResult` |
| `RuleCoordinator` | 规则与结果的单一状态源，UI 与远程端共用 |
| `RuleStore` / `ResultStore` | 原子写入 `files/rules.json` 与 `files/results.json` |
| `VerifyService` | 批量校验并持久化快照 |
| `AgentGateway` | 配对码、token、HTTP 服务生命周期 |

## 数据流

```
UI 操作 ──▶ ViewModel ──▶ RuleCoordinator ──▶ RuleStore
                                  │
                                  ├──▶ RuleEngine ──▶ PrivilegeBroker ──▶ Shizuku
                                  └──▶ ResultStore
                                  ▲
远程客户端 ──▶ GatewayServer ──────┘
```

`RuleCoordinator` 串行化所有写操作，并在变更后更新 `StateFlow<Snapshot>`；
UI 收集该状态，因此本地与远程修改立即一致。

## 后台

- WorkManager 周期任务按设置间隔校验，唯一任务名 `latch-periodic-verify`
- 开机广播触发一次性校验（设置可选）
- 漂移通知使用低优先级通知渠道

## 网关

- 仅在本机监听 `0.0.0.0:8765`，由前台服务保活
- 配对码与 token 只存在内存，断开即失效
- REST 路由集中在 `GatewayServer`，全部写操作转发给 `RuleCoordinator`
- `/meta` 与 `/api` 提供自描述文档，客户端无需预置协议约定
