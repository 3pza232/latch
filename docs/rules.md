# 规则与数据格式

## 三个概念

一条规则由 **能力类型**、**参数** 与 **作用对象** 组成：

| 概念 | 回答的问题 | 取值 |
|---|---|---|
| 能力类型 | 用哪种系统机制限制应用 | AppOps / 权限状态 / 强制停止 / 启停应用 |
| 模式 | AppOps 对该操作的裁决结果 | `ignore` / `allow` / `default` |
| 作用域 | AppOps 的裁决作用在哪个范围 | `uid` / `package` |

### 能力类型

| 类型 | 对应命令 | 行为 |
|---|---|---|
| `appOp` | `cmd appops set [--uid] <pkg> <op> <mode>` | 修改系统操作裁决；不改变运行时权限 |
| `permissionState` | `pm grant` / `pm revoke` 与 AppOps 组合 | 控制运行时权限状态（允许/禁止/仅使用时/询问/大致位置/受限照片） |
| `forceStop` | `am force-stop <pkg>` | 立即结束进程，之后可能自行重启 |
| `setEnabled` | `pm enable` / `pm disable-user --user 0` | 启用或停用整个应用包 |

AppOps 与运行时权限是两套机制：权限型 AppOps 的模式由权限状态决定，单独把 AppOps 设为
`allow` 不会补上权限；反过来，权限被撤销时系统会把对应 AppOps 设为 `ignore`。

### 模式

| 模式 | 含义 |
|---|---|
| `ignore` | 拒绝该操作（通常静默失败） |
| `allow` | 允许该操作 |
| `default` | 清除限制，实际值由系统或权限默认决定 |

`allow` 不等于"授予权限"：例如 `READ_SMS` 的 AppOps 设为 `allow`，仍要求应用持有 `READ_SMS` 权限。

`default` 不是"锁定为 default"：应用后会回读系统实际值，并以实际值作为期望，不会误报漂移。

### 作用域

| 作用域 | 命令 | 影响范围 |
|---|---|---|
| `uid` | `cmd appops set --uid ...` | 整个 uid（同一 uid 的多个包一起生效） |
| `package` | `cmd appops set ...` | 仅该应用包 |

系统 uid 运行的组件、共享 uid 的应用，用 `uid` 模式更可靠；普通应用优先用 `package`。

## 权限

`permissionState` 只保留两个动作：

| 状态 | 行为 | 回读条件 |
|---|---|---|
| `allow` | `pm grant` | 权限 granted |
| `deny` | `pm revoke` + `cmd appops set --uid <op> ignore` | granted=false |

编辑器只提供「允许 / 禁止」两个选项。

说明：

- 权限型操作的 AppOps 模式由系统权限状态推导，直接写 AppOps `allow` 通常无效；
- "仅使用时""每次询问""受限访问"等属于系统权限控制器的状态，
  其中"每次询问"需要 `ONE_TIME` 标志，Shizuku 级工具无法设置；
- 应用未声明的权限（如某些系统组件的 `BODY_SENSORS`）无法授予，只能保持禁止。

## 为什么 AppOps 会显示"系统未接受"

以下情况写入会被系统忽略，回读仍是原值，规则表现为 `DRIFTED`：

- 厂商保护包拒绝放开敏感操作（只允许收紧，不允许 `allow`）；
- 该 uid 已存在 `Uid mode` 限制，包级 `allow` 无法覆盖；
- 权限型 op 的底层权限未授予，AppOps 不会被抬到 allow；
- 系统固定（`SYSTEM_FIXED`）权限与系统 uid 组件无法修改。

这属于系统能力边界，可以保留规则表达意图，或删除规则获得干净状态。

## 防守组合

权限状态与 AppOps 可以叠加，但两者侧重点不同：

| 方式 | 效果 | 风险 |
|---|---|---|
| AppOps `ignore` | 拒绝某类系统操作，应用通常看不到权限变化 | 厂商可能拒绝写入；仅覆盖有 AppOps 的操作 |
| 权限 `deny` | 真正收回运行时权限，应用会看到权限被拒 | 影响功能；系统/受保护应用可能拒绝；可能被重新授予 |
| 权限 `allow` | 强制授予运行时权限 | 覆盖用户隐私选择；系统/受保护应用可能拒绝 |

建议：普通第三方应用可用"权限 `deny` + AppOps `ignore`"做防御纵深；
系统组件与固定权限通常无法通过 `pm revoke` 修改，失败会如实显示漂移。

## 状态

| 状态 | 含义 |
|---|---|
| `PENDING` | 尚未校验，或规则/规则组关闭 |
| `APPLIED` | 回读值与期望一致 |
| `DRIFTED` | 回读值与期望不一致 |
| `FAILED` | 命令执行失败 |
| `DISABLED` | 规则或规则组关闭 |

保存规则（新建或修改）时会立即应用并回读，无需再点「应用」。

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
          "name": "允许相机",
          "targetPackage": "com.example.app",
          "enabled": true,
          "capability": {
            "type": "permissionState",
            "permission": "android.permission.CAMERA",
            "mode": "allow"
          }
        }
      ]
    }
  ]
}
```

- `id` 在文件内唯一；导入时按规则组 `id` 合并：同 id 覆盖，不同 id 追加
- 旧格式的 `grantPermission` / `revokePermission` 会兼容读取为 `allow` / `deny`
- 首次安装没有规则；规则保存在应用私有目录，覆盖安装保留，卸载清除
