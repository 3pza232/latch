# 规则与数据格式

## 三个概念

一条规则由 **能力类型**、**参数** 与 **作用对象** 组成，其中三个容易混淆的概念是：

| 概念 | 回答的问题 | 取值 |
|---|---|---|
| 能力类型 | 用哪种系统机制限制应用 | AppOps / 撤销权限 / 强制停止 / 启停应用 |
| 模式 | AppOps 对该操作的裁决结果 | `ignore` / `allow` / `default` |
| 作用域 | AppOps 的裁决作用在哪个范围 | `uid` / `package` |

### 能力类型

| 类型 | 对应命令 | 行为 |
|---|---|---|
| `appOp` | `cmd appops set [--uid] <pkg> <op> <mode>` | 修改系统操作权限；不改变运行时权限 |
| `revokePermission` | `pm revoke --user 0 <pkg> <permission>` | 真正收回运行时权限，应用会看到权限被拒 |
| `grantPermission` | `pm grant --user 0 <pkg> <permission>` | 强制授予运行时权限，会覆盖系统设置中的拒绝 |
| `forceStop` | `am force-stop <pkg>` | 立即结束进程，之后可能自行重启 |
| `setEnabled` | `pm enable` / `pm disable-user --user 0` | 启用或停用整个应用包 |

AppOps 与运行时权限是两套机制：把 `CAMERA` 的 AppOps 设为 `ignore` 可以阻止相机调用，
但不会在权限页面显示"已拒绝"；反过来，撤销 `CAMERA` 权限会让应用直接拿不到权限。

### 模式

| 模式 | 含义 |
|---|---|
| `ignore` | 拒绝该操作（通常静默失败） |
| `allow` | 允许该操作 |
| `default` | 清除限制，实际值由系统或权限默认决定 |

`allow` 不等于"授予权限"：例如 `READ_SMS` 的 AppOps 设为 `allow`，仍要求应用持有 `READ_SMS` 权限。

`default` 不是"锁定为 default"：应用后会回读系统实际值（可能是 `allow`、`foreground`、
`ignore`），并把该实际值作为这条规则的期望值，因此不会因为系统默认值与字面量不同而误报漂移。

### 作用域

| 作用域 | 命令 | 影响范围 |
|---|---|---|
| `uid` | `cmd appops set --uid ...` | 整个 uid（同一 uid 的多个包一起生效） |
| `package` | `cmd appops set ...` | 仅该应用包 |

系统 uid 运行的组件、共享 uid 的应用，用 `uid` 模式更可靠；
普通应用优先用 `package`，避免误伤同 uid 的其他包。

## 为什么 allow 会显示"系统未接受"

以下情况写入会被系统忽略，回读仍是原值，因此规则表现为 `DRIFTED`：

- 厂商保护包拒绝放开敏感操作（只允许收紧，不允许 `allow`）；
- 该 uid 已存在一条 `Uid mode` 限制，包级 `allow` 无法覆盖它；
- 权限型操作的底层权限未授予，`allow` 不会补上权限；
- 系统固定（`SYSTEM_FIXED`）的权限与系统 uid 组件无法修改。

这属于系统能力边界，可以保留规则表达意图，或删除规则获得干净状态。

## 权限的授予与撤销

权限层与 AppOps 层相互独立：

- **授予权限**（`pm grant`）：强制把运行时权限设为允许，会覆盖系统设置里的拒绝；
- **撤销权限**（`pm revoke`）：真正收回运行时权限，应用会看到权限被拒；
- 对相机、麦克风等权限型 op，系统会根据权限状态自动调整 AppOps 的 uid 模式，
  因此"允许相机"应使用 **授予权限**，而不是 AppOps `allow`。

系统应用、系统 uid、`SYSTEM_FIXED` 权限可能拒绝 `pm grant` / `pm revoke`，失败会表现为漂移。

## 用 revokePermission 做防守

`revokePermission` 与 AppOps 可以叠加，但两者不是替代关系：

| 方式 | 效果 | 风险 |
|---|---|---|
| AppOps `ignore` | 拒绝某类系统操作，应用通常看不到权限变化 | 厂商可能拒绝放开/收紧；系统固定 op 无效 |
| `revokePermission` | 真正收回运行时权限，应用会看到权限被拒 | 影响应用功能；系统/受保护应用会拒绝；更新或系统策略可能重新授予 |
| `grantPermission` | 强制授予运行时权限 | 覆盖用户的隐私选择；系统/受保护应用可能拒绝；请谨慎使用 |

建议：

- 普通第三方应用：可以补充 `revokePermission` 作为加固，尤其是相机、麦克风、通讯录、短信、定位；
- 系统应用、系统 uid 组件、`SYSTEM_FIXED` 权限：`pm revoke` 通常无效，不要指望它；
- 同一目标同时使用 AppOps `ignore` 与撤销权限属于防御纵深，但要接受功能受损与潜在崩溃；
- 优先用 AppOps 做持续约束，撤销权限只在需要"彻底拿掉"时使用。

## 状态

| 状态 | 含义 |
|---|---|
| `PENDING` | 尚未校验，或规则/规则组关闭 |
| `APPLIED` | 回读值与期望一致 |
| `DRIFTED` | 回读值与期望不一致 |
| `FAILED` | 命令执行失败 |
| `DISABLED` | 规则或规则组关闭 |

保存规则（新建或修改）时会立即应用并回读，无需再点「应用」；
规则组与规则均关闭时不会下发。

## JSON 格式

```json
{
  "version": 1,
  "groups": [
    {
      "id": "group-1a2b3c4d",
      "name": "示例规则组",
      "description": "说明文字，可留空",
      "enabled": true,
      "rules": [
        {
          "id": "rule-camera",
          "name": "相机",
          "targetPackage": "com.example.app",
          "enabled": true,
          "capability": {
            "type": "appOp",
            "op": "CAMERA",
            "mode": "ignore",
            "scope": "uid"
          }
        }
      ]
    }
  ]
}
```

- `id` 在文件内唯一；导入时按规则组 `id` 合并：同 id 覆盖，不同 id 追加
- 首次安装没有规则，规则只来自手动创建、JSON 导入或远程接口
- 规则保存在应用私有目录，覆盖安装保留，卸载清除
