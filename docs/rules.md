# 规则与数据格式

## 模型

一条规则由目标应用、能力与启用状态组成：

| 能力 | 字段 | 说明 |
|---|---|---|
| `appOp` | `op`、`mode`、`scope` | AppOps 模式；`scope` 为 `uid` 或 `package` |
| `revokePermission` | `permission` | 撤销运行时权限 |
| `forceStop` | — | 强制停止进程 |
| `setEnabled` | `enabled` | 启用或停用应用 |

AppOps 模式取值：`ignore`、`allow`、`default`。

规则属于规则组。规则组与规则都有 `enabled` 字段，关闭后不参与应用与校验。

## 状态

| 状态 | 含义 |
|---|---|
| `PENDING` | 尚未校验，或规则/规则组关闭 |
| `APPLIED` | 回读值与期望一致 |
| `DRIFTED` | 回读值与期望不一致 |
| `FAILED` | 命令执行失败 |
| `DISABLED` | 规则或规则组关闭 |

应用规则后立即回读；只有回读匹配才记录为 `APPLIED`。
若下发成功但回读不匹配，规则会附带"系统未接受"的原因说明。

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

## 系统限制

以下情况会稳定表现为 `DRIFTED`，属于系统能力边界：

- 受厂商保护的包拒绝卸载、停用或部分 AppOps 写入
- 以系统 uid 运行的组件与带固定标记的权限无法撤销
- 部分敏感权限型 AppOps 在特定目标上不可写

这些规则可以保留以表达意图，也可以删除以获得干净状态。
