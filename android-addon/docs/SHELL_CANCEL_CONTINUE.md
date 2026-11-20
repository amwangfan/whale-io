# Mobile Shell：cancel / continue 与 Core action_status 对齐

对齐对象：Core（whale-core）编排层失败语义——`observe` 可有界重试；`control`/`shell` 超时后须用原幂等键查 `action_status`，`pending`/`unknown` 升 `unknown` 且**禁止自动重放**；仅 `not_found` 可安全新键重试。原则：**结果不明 → 永不自动再造副作用**。

本文件描述 `android-addon` 侧 **ExecutorMock / ShellSession mock** 行为。真 `RootShellDriver` 仍为 TODO。

## 结果码（mock）

| 码 | 含义 |
| --- | --- |
| `OK` | shell 已 start（记录一条命令副作用） |
| `CONTINUE` | 同 session 继续读，**无新命令副作用** |
| `CANCELLED` | cancel 终态回执（mock kill） |
| `OUTCOME_UNKNOWN` | 超时等导致结果不明；幂等缓存后禁自动重试 |
| `DUPLICATE` | 同幂等键命中非 unknown 缓存 |
| `SESSION_DEAD` | session 已 cancel/关闭或不存在 |

## cancel

- **语义**：杀进程（mock：标记 session `CANCELLED`）+ **终态回执**（`CANCELLED` 写入该 session 的幂等键缓存）。
- Core 经 `action_status` 可见终态，不必再猜 pending。
- cancel 之后 `continueRead` 必须失败（`SESSION_DEAD`）。

## continue

- **语义**：同一 `sessionId` 上继续读 stdout/缓冲；**不**再下发新的 shell 命令，不计新副作用。
- 仅 session 仍存活（`RUNNING`，或 timeout 后的 `UNKNOWN` 且尚未 cancel）时成功 → `CONTINUE`。
- 已 `CANCELLED` / `COMPLETED` → 明确失败。

## timeout 且未 cancel

- Mock：`markUnknown(idempotencyKey)` → 缓存 `OUTCOME_UNKNOWN`；session 可仍标 `UNKNOWN`（进程可能还在）。
- 同键再次 `startShell` / `run` → 返回 `OUTCOME_UNKNOWN` 或 `DUPLICATE`，**不**追加 shell 副作用。
- Core：**不得**自动重派发；Mobile 须最终 `cancel` 或上报其它终态。

## observe（只读）vs control/shell

| 能力 | 超时后 |
| --- | --- |
| observe / 只读 TaskDsl | Core 允许**有界**重试（无外部副作用或可安全重复读） |
| control / shell | 先 `action_status` 对原幂等键；`pending`/`unknown` → 升 unknown，**禁止** `send_action` 重放；仅 `not_found` 可新键重试；`executed` → 当成功 |

## 真驱动缺口

`RootShellDriver` 仍为 stub：`dumpsys` / `uiautomatorDump` 未实现；连续 shell 的真实进程、PTY、cancel 信号与 stdout 分片均待实现。当前仅 ExecutorMock 内存 API 供离线对齐与测试。
