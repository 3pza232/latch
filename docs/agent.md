# 局域网接口与 CLI

## 开启连接

设置页 →「局域网连接」→「连接」。
面板显示地址（含端口）与 6 位配对码，点「复制连接信息」一次复制两者。
客户端配对成功后面板自动关闭，设置页显示「已连接」与「断开」。

复制的内容形如：

```
地址：http://192.168.10.21:42047
配对码：541593
```

端口由系统随机分配（每次连接可能不同），避免固定端口被扫描；
配对码一次性使用，失败 5 次后自动更换。

## 鉴权

1. `GET /meta`：无需鉴权，返回名称、版本、配对方式与文档入口
2. `POST /pair`：请求体 `{"code":"<配对码>"}`，返回 `{"token":"..."}`
3. 其余请求带 `Authorization: Bearer <token>`

配对码错误 5 次后自动更换；断开连接会吊销 token。
服务仅监听局域网，明文 HTTP，只在可信网络使用。

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/meta` | 自描述元信息（无需鉴权） |
| GET | `/api` | 完整接口清单、参数与示例（需鉴权） |
| POST | `/pair` | 用配对码换 token |
| GET | `/api/state` | 网关与 Shizuku 状态、分组计数、汇总 |
| GET | `/api/logs?limit=200` | 最近事件（只读、已脱敏） |
| GET | `/api/stats` | 运行统计：轮次、活跃天数、最近 7 天 |
| GET | `/api/backups` | 规则快照列表（新到旧） |
| POST | `/api/backups` | 立即创建手动快照 |
| POST | `/api/backups/{id}/restore` | 恢复快照（可限定规则组） |
| GET | `/api/rules` | 完整规则文档 |
| POST | `/api/import` | 用完整规则文档导入/合并规则 |
| POST | `/api/rules` | 新增或更新规则；冲突时返回 409 |
| POST | `/api/rules/{id}/enabled` | 启用/停用规则 |
| DELETE | `/api/rules/{id}` | 删除规则 |
| POST | `/api/groups/{id}/enabled` | 启用/停用规则组（启用时自动应用） |
| POST | `/api/groups/{id}/apply` | 应用规则组并校验 |
| POST | `/api/verify` | 校验全部规则 |
| POST | `/api/repair` | 校验并自动修复漂移，返回仍失败的条目 |

示例：

```http
GET /api/state
Authorization: Bearer <token>

POST /api/rules
{"groupId":"<groupId>","rule":{"name":"允许相机","targetPackage":"com.example.app","enabled":true,"capability":{"type":"permissionState","permission":"android.permission.CAMERA","mode":"allow"}}}
```

## 冲突处理

`POST /api/rules` 保存的规则与现有规则冲突时返回 **409**，并列出冲突项：

```json
{"ok":false,"error":"rule conflicts with existing rules","conflicts":[
  {"ruleId":"rule-a","ruleName":"禁止相机","groupId":"g","groupName":"组","reason":"AppOps CAMERA"}
]}
```

在请求体中加入 `resolve` 后重试：

- `"resolve":"overwrite"`：新规则覆盖现有规则；
- `"resolve":"keep"`：保留现有规则，新规则保存为被覆盖。

`GET /api/state` 的组与汇总中包含 `suppressed` 计数。

## 日志接口

`GET /api/logs` 返回最近事件，按时间倒序，`limit` 上限 1000，默认 200：

```json
{"ok":true,"count":2,"events":[
  {"id":"…","ts":1760000000000,"level":"INFO","source":"periodic","action":"repair",
   "outcome":"ok","detail":"checked=75 applied=75 drifted=0 failed=0 repaired=0","durationMs":2567}
]}
```

- 只读，需 Bearer token；
- 已脱敏：不返回命令原文、stdout/stderr、token、配对码；
- 详情字段（命令与输出）只在应用内的日志页可见。

## 备份与恢复接口

恢复只改规则配置，默认不下发系统命令；`apply: true` 才会在恢复后立即应用。
请求体中的 `groups` 省略时恢复整个文档，给出组 id 列表时只恢复这些组：

```http
POST /api/backups/b-1760000000000-ab12cd34/restore
{"groups":["adslot-lock"],"apply":false}
```

返回新增、替换、删除的组数，以及恢复后的组数与规则数。
恢复前会自动创建一份 `pre_restore` 快照，因此这次恢复本身也可以撤销。

## CLI

`tools/latch-remote.ps1`（PowerShell 7+）通过局域网管理规则。
首次用「复制连接信息」里的地址与配对码配对，之后只需地址（token 已保存）：

```powershell
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Code 541593 -Command status

./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command status
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command logs
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command backups
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command backup
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command restore -Backup <backupId> -Group adslot-lock
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command rules
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command apply   -Group adslot-lock
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command verify
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command repair
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command disable -Rule <ruleId>
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command enable  -Rule <ruleId>
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command enable-group  -Group <groupId>
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command disable-group -Group <groupId>
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command add     -File rule.json
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command import  -File rules.json
./tools/latch-remote.ps1 -Address 192.168.10.21:42047 -Command delete  -Rule <ruleId>
```

首次配对成功后 token 保存到 `tools/.latch-token`（已在 `.gitignore` 中忽略）。

## 另一个 CLI

`tools/latch-agent.ps1` 通过 USB adb 读取应用私有文件，离线管理与审计规则，
依赖 debug 版本应用（`run-as`），不需要开网关。

## 注意

- 应用与校验依赖 Shizuku 运行；Shizuku 未启动时命令会失败
- 网关只处理规则配置，不代替 Shizuku 执行系统命令
