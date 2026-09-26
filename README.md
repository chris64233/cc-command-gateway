# cc-command-gateway

管理受控设备、操作租约和指令执行记录。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 设备与租约

- 设备通过 `POST /api/devices` 以唯一编号登记，重复登记返回 `409 DEVICE_EXISTS`。
- 客户端通过 `POST /api/devices/{deviceNumber}/leases` 申请带明确到期时间（`ttlMillis`）的独占租约。
- 每台设备同一时刻最多一个有效租约；租约未到期时其他客户端申请返回 `409 LEASE_ACTIVE`。
- 每次成功获取租约，设备的栅栏令牌（fencing token）严格递增（+1），并随租约下发。
- 租约到期后新客户端可以取得更大令牌；旧租约没有续期入口，过期后不能复活。

### 指令提交

- `POST /api/devices/{deviceNumber}/commands` 必须携带 `leaseId`、`fencingToken`、客户端单调递增的 `sequence`、`idempotencyKey` 和 `payload`。
- 拒绝条件（均为 `409`）：令牌不是设备当前令牌（`FENCING_TOKEN_MISMATCH`）、租约已过期（`LEASE_EXPIRED`）、序号不大于该租约最后接受序号（`STALE_SEQUENCE`）。
- 幂等重放：同一租约下相同幂等键且指令内容相同，返回首次接受的记录，不重复推进序号；相同键但内容不同返回 `409 IDEMPOTENCY_CONFLICT`。
- 并发提交通过对设备行的悲观写锁（`PESSIMISTIC_WRITE`）串行化，保证接受顺序、最后序号与持久化指令一致。

### 设备回执

- `POST /api/devices/{deviceNumber}/commands/{commandId}/receipts` 携带 `eventId`、`fencingToken`、`status`、`detail`。
- 回执可乱序到达，但只能更新对应的已接受指令；终态（`SUCCEEDED`/`FAILED`）回执不能被另一终态覆盖（`409 TERMINAL_STATE_LOCKED`）。
- 未知指令返回 `404 COMMAND_NOT_FOUND`；令牌与指令接受时的令牌不匹配返回 `409 FENCING_TOKEN_MISMATCH`。
- 相同回执事件内容一致时为幂等重放，返回首次记录；内容冲突返回 `409 RECEIPT_CONFLICT`。

### 查询

- `GET /api/devices/{deviceNumber}`：设备当前栅栏令牌与当前租约（含到期时间、最后接受序号、是否有效）。
- `GET /api/devices/{deviceNumber}/commands`：按序号排序的指令时间线，含每条指令的回执列表。

### 并发与事务

- 所有数据使用 JPA + H2 持久化；`(lease_id, idempotency_key)` 与 `(command_id, event_id)` 建有唯一约束兜底。
- 租约获取、令牌递增、指令接受在同一事务内完成，并通过设备行悲观锁保证串行；回执处理对指令行加悲观锁。
