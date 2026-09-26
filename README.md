# cc-command-gateway

管理受控设备、独占操作租约、栅栏令牌（fencing token）、指令顺序与设备回执。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- Spring Data JPA + H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 业务规则

### 设备登记

- 设备以唯一编号（`deviceId`）登记，重复登记返回冲突。

### 独占租约与栅栏令牌

- 客户端可为设备申请带明确 `expiresAt`（必须晚于当前时间）的独占租约，每台设备同时最多一个有效租约。
- 每次成功获取租约生成一个在该设备上**严格递增**的栅栏令牌，并返回新的租约标识。
- 租约到期后不支持续期复活：旧租约永久失效，新客户端可获取更大令牌的新租约。
- 设备行在租约获取与令牌递增期间使用悲观写锁，租约、令牌、设备引用在同一事务内提交。

### 指令提交

提交指令必须携带：`leaseId`、`fenceToken`、客户端单调递增序号 `clientSeq`、`idempotencyKey` 与指令内容 `payload`；可选 `deadlineAt`（指令截止时间，必须晚于当前时间，否则 `deadline_in_past`）。以下情况拒绝（409）：

- 令牌与租约令牌不符（`fence_token_mismatch`），或令牌不是设备当前令牌（`stale_fence_token`，旧控制者的指令被拒绝）；
- 租约不属于该设备或租约已过期（`lease_expired`）；
- `clientSeq` 不大于该租约最后接受序号（`stale_client_seq`）。

幂等与顺序：

- 同一 `(deviceId, idempotencyKey)` 且租约、令牌、序号、内容、截止时间完全相同的重放，返回首次记录，不重复推进序号与接受顺序；
- 同一幂等键但请求内容不同返回冲突（`idempotency_conflict`）；
- 设备维护全局递增的 `acceptOrder`。并发提交在设备行悲观锁 + 单事务内完成判定与落库，保证接受顺序、租约最后序号与持久化指令一致；唯一约束作为最后防线。

### 指令生命周期与派发

指令接受后经历以下状态：

```
PENDING(待派发) → DISPATCHED(已派发) → ACKNOWLEDGED(设备已开始) → SUCCEEDED | FAILED
     │                  │
     └────── CANCELLED(主动取消) / TIMED_OUT(超时取消) ←─────────┘
```

- `POST .../commands/{commandUuid}/dispatch` 将待派发指令标记为已派发并记录 `DISPATCHED` 事件；重复派发幂等返回，非待派发状态返回 `dispatch_state_conflict`；
- 终态为 `SUCCEEDED` / `FAILED` / `CANCELLED` / `TIMED_OUT`，终态不可逆；
- 每次状态变化都写入指令生命周期事件（`ACCEPTED` / `DISPATCHED` / `CANCELLED` / `TIMED_OUT`），事件编号全局唯一：接受、派发、超时事件使用确定性编号（`accept:/dispatch:/timeout:{commandUuid}`），取消事件使用客户端取消号。

### 指令取消

`POST .../commands/{commandUuid}/cancel`，体：`{ "leaseId", "fenceToken", "cancelId", "reason"? }`。

- 只能取消尚未进入设备执行阶段的指令（`PENDING` / `DISPATCHED`）；已开始的指令返回 `command_already_started`，已到其他终态返回 `command_already_terminal` / `command_already_cancelled`，绝不把已执行指令伪装成取消成功；
- 取消方必须持有设备**当前有效租约**（过期返回 `lease_expired`，非当前租约返回 `stale_lease`），且栅栏令牌须与租约令牌一致（`fence_token_mismatch`）并**不小于原指令令牌**（`fence_token_too_low`）——因此接管设备的新控制者可以取消旧控制者遗留的未执行指令；
- 取消号 `cancelId` 幂等：同一取消号重放返回首次结果；同一取消号绑定到不同指令返回 `cancel_event_conflict`；
- 取消与设备开始回执（`ACK`）在指令行悲观写锁内竞争，二者只会有一个生效：取消先落库则迟到的设备回执成为异常记录，回执先落库则取消请求被拒绝。

### 指令超时

- 每条指令可在提交时声明 `deadlineAt`；超时判定与取消、回执共用同一个注入的 `Clock`（统一时间来源）；
- `POST .../timeout-scan` 扫描本设备截止时间已过、仍未进入设备执行阶段（`PENDING` / `DISPATCHED`）的指令，置为 `TIMED_OUT` 并记录 `TIMED_OUT` 事件；
- 超时扫描与主动取消共享同一终态竞争规则（指令行悲观锁 + 状态重校验），且超时事件使用确定性事件编号 `timeout:{commandUuid}`，扫描可安全重试，不会重复产生取消事件。

### 设备回执

回执携带：指令编号、租约标识、栅栏令牌、回执事件编号 `eventId`、类型（`ACK`/`SUCCEEDED`/`FAILED`）与内容。

- 回执可乱序到达，每条只更新对应已接受指令，时间线按设备接受顺序展示并附带回执列表；
- 状态流转：`PENDING → DISPATCHED → ACKNOWLEDGED → SUCCEEDED|FAILED`，`ACK` 后仍可到达终态；
- 指令一旦进入 `SUCCEEDED`/`FAILED`，任何其他终态或迟到 `ACK` 均被拒绝（`terminal_receipt`）；
- 指令已 `CANCELLED`/`TIMED_OUT` 后到达的迟到回执（包括迟到的成功回执）作为**异常记录**保留（回执标记 `late=true`），但不改变指令状态，指令不会被改回成功；
- 未知指令（或不属于该设备）返回 404；令牌与指令接受时令牌不匹配返回 `fence_token_mismatch`；
- 相同 `eventId` 的重复回执（指令、类型、内容一致）按幂等返回首次记录；同一事件编号绑定不同指令或不同内容返回 `receipt_event_conflict`。回执更新在指令行悲观锁事务内进行。

### 查询

- `GET /api/devices/{deviceId}/status`：设备当前栅栏令牌与当前有效租约；
- `GET /api/devices/{deviceId}/leases/current`：当前有效租约详情（含最后接受序号）；
- `GET /api/devices/{deviceId}/commands`：按接受顺序排列的指令时间线。每条指令包含：
  - `events`：生命周期事件（接受 `ACCEPTED`、派发 `DISPATCHED`、取消 `CANCELLED`、超时 `TIMED_OUT`），含事件编号、栅栏令牌、详情与发生时间；
  - `receipts`：设备回执（设备执行进度），迟到回执以 `late=true` 标识为异常记录。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 登记设备，体：`{ "deviceId": "..." }` |
| POST | `/api/devices/{deviceId}/leases` | 申请独占租约，体：`{ "clientId": "...", "expiresAt": "2026-10-01T00:00:00Z" }` |
| GET | `/api/devices/{deviceId}/leases/current` | 查询当前租约 |
| GET | `/api/devices/{deviceId}/status` | 查询当前令牌与租约 |
| POST | `/api/devices/{deviceId}/commands` | 提交指令，体：`{ "leaseId", "fenceToken", "clientSeq", "idempotencyKey", "payload", "deadlineAt"? }` |
| GET | `/api/devices/{deviceId}/commands` | 指令时间线（含生命周期事件与回执） |
| POST | `/api/devices/{deviceId}/commands/{commandUuid}/dispatch` | 派发待派发指令（幂等） |
| POST | `/api/devices/{deviceId}/commands/{commandUuid}/cancel` | 取消未执行指令，体：`{ "leaseId", "fenceToken", "cancelId", "reason"? }` |
| POST | `/api/devices/{deviceId}/timeout-scan` | 超时扫描，返回本次被置为超时的指令 |
| POST | `/api/devices/{deviceId}/receipts` | 上报回执，体：`{ "commandUuid", "leaseId", "fenceToken", "eventId", "kind", "content" }` |

错误响应统一为 `{ "code": "...", "message": "..." }`：参数错误为 400 `invalid_request`，资源不存在为 404 `not_found`，业务冲突为 409 及上表中的错误码。
