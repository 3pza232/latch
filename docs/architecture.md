# 架构设计

## 分层

```
core/model     领域模型：Rule / Capability / RuleGroup / RuleState
core/result    错误分类：LatchError / ShellErrorClassifier
core/log       事件模型：LatchEvent / EventLog
core/stats     统计聚合：按天计数与摘要（纯函数）
core/rules     冲突判定、覆盖解析、受保护目标、导入校验与 uid 查询接口（纯函数）
privilege      特权通道：PrivilegeBroker 与 Shizuku 实现
engine         命令映射、输出解析、应用与校验、结果到事件的映射
domain         RuleCoordinator：规则状态中枢
data           规则 / 结果 / 设置 / 事件日志 / 备份快照存储，JSON 编解码，已安装应用
gateway        局域网网关：配对、鉴权、REST、前台服务
ui             Compose 界面、ViewModel、组件
work           后台校验 Worker 与调度
receiver       开机自检
notify         漂移通知
```

依赖方向单向：`ui → domain → engine → privilege`，`domain → data` 只通过存储抽象。
UI 不拼接命令，engine 不直接持有特权，只有 `CapabilityCommands` 生成命令；
批量校验、备份与统计的编排都在 domain，engine 只负责单条命令与回读。

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

快照分两类：边界快照（每小时整点、每天 0 点各一份，在边界内第一次有活动时补拍，
不要求规则变更）与变更快照（文档落盘后创建，相同内容去重、短时间内合并，保留最近
100 份）。恢复点按“槽位”解析：取该边界最近的有效快照，目标日期没有快照时沿用更早
的一份，直到被更新的快照顶替。恢复由 `RestoreService` 规划，写回前先创建
`pre_restore` 快照；恢复本身不执行系统命令。

边界快照只由应用活动触发（启动、规则变更、打开备份页），与定期自检完全独立：
定期自检只负责规则漂移的校验与修复，不创建、不检查任何快照。

清理时序：每日快照创建后立即把每日快照裁剪到最新 31 份（覆盖 30 天槽位）；
整点快照只保留当前小时的 1 份，午夜创建每日快照后会立刻删除上一小时的整点快照。
备份页下方只列出非边界快照（变更/手动/恢复前/迁移），边界快照作为恢复点不可手动删除。

## 后台

- WorkManager 周期任务按设置间隔校验，唯一任务名 `latch-periodic-verify`
- 校验后发现漂移会立即重新应用；仍失败的条目通过通知提示
- 开机广播触发一次性校验（设置可选）
- 周期校验使用与手动校验相同的 `RuleEngine`，不经过 UI
- 漂移通知使用低优先级通知渠道

## 网关

- 监听 `0.0.0.0` 上由系统分配的随机端口，由前台服务保活
- 配对码与 token 只存在内存，断开即失效
- REST 路由集中在 `GatewayServer`，全部写操作转发给 `RuleCoordinator`
- `/meta` 与 `/api` 提供自描述文档，客户端无需预置协议约定
