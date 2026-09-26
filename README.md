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

提交指令必须携带：`leaseId`、`fenceToken`、客户端单调递增序号 `clientSeq`、`idempotencyKey` 与指令内容 `payload`；可选 `deadlineAt` 声明截止时间（超时后由超时扫描取消，见下文）。以下情况拒绝（409）：

- 令牌与租约令牌不符（`fence_token_mismatch`），或令牌不是设备当前令牌（`stale_fence_token`，旧控制者的指令被拒绝）；
- 租约不属于该设备或租约已过期（`lease_expired`）；
- `clientSeq` 不大于该租约最后接受序号（`stale_client_seq`）。

幂等与顺序：

- 同一 `(deviceId, idempotencyKey)` 且租约、令牌、序号、内容、截止时间完全相同的重放，返回首次记录，不重复推进序号与接受顺序；
- 同一幂等键但请求内容不同返回冲突（`idempotency_conflict`）；
- 设备维护全局递增的 `acceptOrder`。并发提交在设备行悲观锁 + 单事务内完成判定与落库，保证接受顺序、租约最后序号与持久化指令一致；唯一约束作为最后防线。

### 指令生命周期与派发

指令接受后的状态机：

```
PENDING → DISPATCHED → ACKNOWLEDGED → SUCCEEDED | FAILED
   └──────────┴──────────┴→ CANCELLED（客户端取消）
   └──────────┴──────────┴→ TIMED_OUT（超时取消）
```

- `PENDING`（待派发）→ `DISPATCHED`（已派发）：`POST .../commands/{commandUuid}/dispatch` 派发指令并记录 `dispatchedAt`；重复派发幂等返回，已进入执行阶段或终态的指令拒绝（`command_not_dispatchable`）；
- `ACKNOWLEDGED` 表示设备已开始执行（收到 `ACK` 回执），此后不可取消；
- 终态为 `SUCCEEDED` / `FAILED` / `CANCELLED` / `TIMED_OUT`。

### 指令取消

`POST .../commands/{commandUuid}/cancel`，体：`{ "leaseId", "fenceToken", "cancelId", "reason" }`。

- 客户端只能使用**当前有效租约**取消，且栅栏令牌必须不小于原指令接受时的令牌（租约过期 `lease_expired`、令牌不符 `fence_token_mismatch`、旧令牌 `stale_fence_token`）；新租约接管设备后可凭更大的令牌取消旧租约时期接受的指令；
- 只有尚未进入设备执行阶段的指令（`PENDING` / `DISPATCHED`）可取消；已 `ACKNOWLEDGED` 或已到终态的指令拒绝（`command_not_cancellable`）——已执行的指令不得伪装成取消成功；
- 取消号 `cancelId` 幂等：同一取消号对同一指令的重放返回首次取消记录；取消号绑定到不同指令返回 `cancel_event_conflict`；每条指令最多一条取消事件（唯一约束兜底）；`timeout:` 前缀保留给超时扫描，客户端使用返回 `reserved_cancel_id`；
- 取消与设备回执在指令行悲观锁事务内竞争，只会形成「已取消」或「已开始」其中一种结果。

### 截止时间与超时扫描

- 提交指令时可声明 `deadlineAt`；`POST /api/devices/{deviceId}/timeout-scan` 扫描该设备上已过截止时间且仍可取消的指令，逐条置为 `TIMED_OUT` 并记录取消事件；
- 扫描与主动取消使用同一统一时钟（`Clock`），并共享同一终态竞争规则（指令行悲观锁 + 状态判定）：与客户端取消、设备回执并发时只有一个获胜；
- 超时取消事件使用确定性取消号 `timeout:{commandUuid}`，扫描可安全重试，不重复产生取消事件；已被客户端取消或已执行完成的指令不会被重复扫描取消。

### 设备回执

回执携带：指令编号、租约标识、栅栏令牌、回执事件编号 `eventId`、类型（`ACK`/`SUCCEEDED`/`FAILED`）与内容。

- 回执可乱序到达，每条只更新对应已接受指令，时间线按设备接受顺序展示并附带回执列表；
- 状态流转：`PENDING → DISPATCHED → ACKNOWLEDGED → SUCCEEDED|FAILED`，`ACK` 后仍可到达终态；
- 指令一旦进入 `SUCCEEDED`/`FAILED`，任何其他终态或迟到 `ACK` 均被拒绝（`terminal_receipt`）；
- 已取消/已超时（`CANCELLED`/`TIMED_OUT`）指令的迟到回执作为**异常记录**保留（`anomalous=true`），出现在时间线中，但不会把指令改回成功；
- 未知指令（或不属于该设备）返回 404；令牌与指令接受时令牌不匹配返回 `fence_token_mismatch`；
- 相同 `eventId` 的重复回执（指令、类型、内容一致）按幂等返回首次记录；同一事件编号绑定不同指令或不同内容返回 `receipt_event_conflict`。回执更新在指令行悲观锁事务内进行。

### 查询

- `GET /api/devices/{deviceId}/status`：设备当前栅栏令牌与当前有效租约；
- `GET /api/devices/{deviceId}/leases/current`：当前有效租约详情（含最后接受序号）；
- `GET /api/devices/{deviceId}/commands`：按接受顺序排列的指令时间线。每条指令包含状态、`acceptedAt`、`dispatchedAt`、`deadlineAt`、取消事件（`cancel`：取消号、类型 `CLIENT`/`TIMEOUT`、原因、取消时间）与回执列表（含 `anomalous` 异常迟到标记），可明确区分接受、派发、取消、超时、设备执行及异常迟到回执。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/devices` | 登记设备，体：`{ "deviceId": "..." }` |
| POST | `/api/devices/{deviceId}/leases` | 申请独占租约，体：`{ "clientId": "...", "expiresAt": "2026-10-01T00:00:00Z" }` |
| GET | `/api/devices/{deviceId}/leases/current` | 查询当前租约 |
| GET | `/api/devices/{deviceId}/status` | 查询当前令牌与租约 |
| POST | `/api/devices/{deviceId}/commands` | 提交指令，体：`{ "leaseId", "fenceToken", "clientSeq", "idempotencyKey", "payload", "deadlineAt"? }` |
| GET | `/api/devices/{deviceId}/commands` | 指令时间线 |
| POST | `/api/devices/{deviceId}/commands/{commandUuid}/dispatch` | 派发指令到设备（幂等） |
| POST | `/api/devices/{deviceId}/commands/{commandUuid}/cancel` | 取消指令，体：`{ "leaseId", "fenceToken", "cancelId", "reason"? }` |
| POST | `/api/devices/{deviceId}/timeout-scan` | 扫描并取消该设备已过截止时间的指令，返回本次产生的取消事件 |
| POST | `/api/devices/{deviceId}/receipts` | 上报回执，体：`{ "commandUuid", "leaseId", "fenceToken", "eventId", "kind", "content" }` |

错误响应统一为 `{ "code": "...", "message": "..." }`：参数错误为 400 `invalid_request`，资源不存在为 404 `not_found`，业务冲突为 409 及上表中的错误码。
