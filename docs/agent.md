# 局域网接口与 CLI

## 开启连接

设置页 →「局域网连接」→「连接」。
面板显示完整地址、端口（默认 `8765`）与 6 位配对码，等待客户端接入。
客户端配对成功后面板自动关闭，设置页显示「已连接」与「断开」。

需要把 **完整 IP + 端口 + 配对码** 交给客户端，例如：

```
192.168.10.21:8765
配对码 123456
```

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
| GET | `/api/rules` | 完整规则文档 |
| POST | `/api/import` | 用完整规则文档导入/合并规则 |
| POST | `/api/rules` | 新增或更新规则（保存后立即应用并返回结果） |
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

## CLI

`tools/latch-remote.ps1`（PowerShell 7+）通过局域网管理规则：

```powershell
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Code 123456 -Command status

./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command status
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command rules
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command apply   -Group adslot-lock
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command verify
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command repair
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command disable -Rule <ruleId>
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command enable  -Rule <ruleId>
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command enable-group  -Group <groupId>
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command disable-group -Group <groupId>
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command add     -File rule.json
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command import  -File rules.json
./tools/latch-remote.ps1 -Address 192.168.10.21:8765 -Command delete  -Rule <ruleId>
```

首次配对成功后 token 保存到 `tools/.latch-token`（已在 `.gitignore` 中忽略）。

## 另一个 CLI

`tools/latch-agent.ps1` 通过 USB adb 读取应用私有文件，离线管理与审计规则，
依赖 debug 版本应用（`run-as`），不需要开网关。

## 注意

- 应用与校验依赖 Shizuku 运行；Shizuku 未启动时命令会失败
- 网关只处理规则配置，不代替 Shizuku 执行系统命令
