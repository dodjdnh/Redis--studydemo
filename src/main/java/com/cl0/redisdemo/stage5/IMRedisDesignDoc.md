# IM Redis Design Doc

## 1. 设计目标

本文档总结 `redisdemo` 阶段五中的 IM Redis 设计，并对应到 `RealTimeCommunicationService` 后续可落地方向。

目标不是把所有数据都放 Redis，而是把 Redis 用在最适合它的位置：

- 高频状态：在线状态、心跳、未读数。
- 原子计数：消息序号、未读数。
- 跨节点协调：Pub/Sub 房间广播。
- 可靠补偿：Stream 离线消息和重试任务。
- 缓存防护：用户、群组、会话成员等读多写少数据。

## 2. Key 设计总览

| Key | 类型 | Value / Field | TTL | 用途 |
|-----|------|---------------|-----|------|
| `user:online:{userId}` | String | `nodeId` | 90 秒 | 用户在线状态和所在 RTC 节点 |
| `msg:seq:{roomId}` | String | 数字序号 | 不设置 | 房间内严格递增消息序号 |
| `unread:{userId}` | Hash | field=`sessionId`，value=`count` | 可选 | 用户各会话未读数 |
| `room:members:{roomId}` | Set | member=`userId` | 可选 | 房间成员集合 |
| `im:room:broadcast` | Pub/Sub channel | JSON 消息 | 不持久化 | 多 RTC 节点房间广播通知 |
| `offline:stream:{userId}` | Stream | 离线消息 field-value | 按策略裁剪 | 用户离线消息补推 |
| `msg:dedup:{messageId}` | String | `1` 或状态值 | 1-7 天 | 消息幂等，防重复处理 |
| `user:info:{userId}` | String / Hash | 用户基础信息 | 30 分钟 + 随机抖动 | 用户信息缓存 |
| `group:info:{groupId}` | String / Hash | 群基础信息 | 30 分钟 + 随机抖动 | 群信息缓存 |
| `session:members:{sessionId}` | Set | member=`userId` | 短 TTL | 会话成员缓存 |
| `lock:rebuild:{biz}:{id}` | String / RLock | 锁 token | 短 TTL / 看门狗 | 热点缓存重建锁 |

## 3. 在线状态

### 3.1 Key

```text
user:online:{userId}
```

示例：

```text
user:online:10001 = rtc-node-1
TTL = 90 秒
```

### 3.2 写入时机

WebSocket 握手成功后：

```text
SET user:online:{userId} {nodeId} EX 90
```

### 3.3 续期时机

客户端心跳：

```text
EXPIRE user:online:{userId} 90
```

### 3.4 删除时机

用户主动退出、WebSocket 关闭、服务端检测连接断开：

```text
DEL user:online:{userId}
```

### 3.5 设计原因

在线状态是短生命周期状态，适合用 TTL 自动兜底。

如果客户端断网、进程崩溃或服务没收到 logout，TTL 到期后用户会自动变成离线。

## 4. 消息序号

### 4.1 Key

```text
msg:seq:{roomId}
```

示例：

```text
INCR msg:seq:room-2001
```

返回：

```text
1
2
3
...
```

### 4.2 设计原因

房间消息需要严格递增序号，用于：

- 客户端排序。
- 断线重连补拉。
- ACK 对账。
- 未读游标推进。

不要用 ZSet score 或时间戳做严格消息序号。同一毫秒可能有多条消息，ZSet score 相同后不能保证真实发送顺序。

## 5. 未读数

### 5.1 Key

```text
unread:{userId}
```

示例：

```text
unread:10002
  room-2001 -> 3
  room-2002 -> 8
```

### 5.2 新消息到达

```text
HINCRBY unread:{userId} {sessionId} 1
```

### 5.3 用户进入会话

```text
HDEL unread:{userId} {sessionId}
```

### 5.4 设计原因

一个用户有多个会话，每个会话一个未读数，Hash 结构最自然。

未读数加一必须用 `HINCRBY`，不要先 `HGET` 再 `HSET`，否则高并发下会丢计数。

## 6. 房间成员

### 6.1 Key

```text
room:members:{roomId}
```

示例：

```text
SADD room:members:room-2001 10001
SADD room:members:room-2001 10002
```

### 6.2 使用方式

判断用户是否在房间：

```text
SISMEMBER room:members:{roomId} {userId}
```

获取成员数量：

```text
SCARD room:members:{roomId}
```

大房间遍历：

```text
SSCAN room:members:{roomId}
```

### 6.3 设计原因

Set 天然去重，适合成员集合。

不要对大房间频繁使用 `SMEMBERS`，会一次性拉取所有成员，可能造成 Redis 和应用内存压力。

## 7. 房间广播

### 7.1 频道

```text
im:room:broadcast
```

### 7.2 消息格式示例

```json
{
  "roomId": "room-2001",
  "senderUserId": "10001",
  "targetUserId": "10002",
  "targetNodeId": "rtc-node-2",
  "seq": 15,
  "content": "hello room"
}
```

### 7.3 流程

```text
1. 当前 RTC 节点收到房间消息
2. INCR msg:seq:{roomId} 生成 seq
3. 读取 room:members:{roomId}
4. 对每个成员读取 user:online:{userId}
5. 在线用户发布 Pub/Sub 广播
6. 所有 RTC 节点收到广播
7. targetNodeId 等于自己的节点才推本机 WebSocket
```

### 7.4 设计原因

Pub/Sub 适合实时跨节点通知。

但 Pub/Sub 不持久化，所以不能作为离线消息存储。

## 8. 离线消息与可靠补推

### 8.1 Key

```text
offline:stream:{userId}
```

### 8.2 写入

```text
XADD offline:stream:{userId} * messageId {messageId} content {content}
```

### 8.3 消费

```text
XGROUP CREATE offline:stream:{userId} rtc-delivery-group 0-0
XREADGROUP GROUP rtc-delivery-group {consumerName} COUNT 10 STREAMS offline:stream:{userId} >
XACK offline:stream:{userId} rtc-delivery-group {recordId}
```

### 8.4 设计原因

Stream 支持持久化、消费者组和 ACK。

用户不在线时，消息写入 Stream；用户上线后读取并补推；推送成功后 `XACK`。

相比 List，Stream 不会因为“取出后服务崩溃”而直接丢消息。

## 9. 消息幂等

### 9.1 Key

```text
msg:dedup:{messageId}
```

### 9.2 基础做法

```text
SET msg:dedup:{messageId} 1 NX EX 86400
```

第一次成功：

```text
继续处理消息
```

重复请求失败：

```text
直接跳过
```

### 9.3 生产注意

简单写 `1` 只能表示“处理过”。

更严谨的做法是保存状态：

```text
PROCESSING
SUCCESS
FAILED
```

或者数据库对 `messageId` 建唯一索引兜底。

## 10. 缓存设计

### 10.1 用户信息

```text
user:info:{userId}
```

适合缓存：

- 昵称。
- 头像。
- 简介。

TTL：

```text
30 分钟 + 随机 0~5 分钟
```

### 10.2 群信息

```text
group:info:{groupId}
```

适合缓存：

- 群名。
- 群头像。
- 群公告。

### 10.3 会话成员

```text
session:members:{sessionId}
```

权限类数据要谨慎。

如果成员变更，必须及时删除或刷新缓存，否则可能出现：

```text
用户已经退群，但缓存还认为他在群里。
```

### 10.4 防护策略

| 问题 | 解决方式 |
|------|----------|
| 缓存穿透 | 布隆过滤器 + 空值缓存 |
| 缓存击穿 | Redisson 互斥锁 + 双重检查 |
| 缓存雪崩 | TTL 随机抖动 + 分批预热 |
| 高频读取 | Caffeine 本地缓存 + Redis 远程缓存 |

## 11. 分布式锁

### 11.1 重试任务锁

```text
RTC:DELIVERY:RETRY:LOCK
```

建议使用 Redisson：

```text
RLock lock = redissonClient.getLock("RTC:DELIVERY:RETRY:LOCK")
tryLock(2, TimeUnit.SECONDS)
```

不要随便指定 leaseTime，否则可能关闭看门狗自动续期。

### 11.2 缓存重建锁

```text
lock:rebuild:user:{userId}
lock:rebuild:group:{groupId}
lock:rebuild:session-members:{sessionId}
```

抢到锁后必须二次查 Redis，避免重复查数据库。

## 12. 与 RealTimeCommunicationService 的落地映射

| RTC 现状 | 建议演进 |
|----------|----------|
| `ChannelManager` 只保存本机连接 | 继续保留本机 Channel，但增加 `user:online:{userId}` 记录所在节点 |
| 用户不在本机就可能误判离线 | 先查 Redis 在线状态，再通过 Pub/Sub 广播给目标节点 |
| 重试任务用手写 `setIfAbsent` 锁 | 可升级 Redisson `tryLock` + 看门狗 |
| 投递状态存在 Redis Hash | 保留，但大 Hash 场景要考虑 `HSCAN` |
| 重试索引用 Set | 可改为 ZSet，score 放 `nextRetryAt` |
| 无 Pub/Sub | 增加 `im:push` 或 `im:room:broadcast` |
| 无 Stream | 增加 `offline:stream:{userId}` 做离线补推 |
| 无完整缓存防护 | 为用户、群组、会话成员缓存补穿透/击穿/雪崩防护 |

## 13. 推荐消息发送链路

```text
1. 接收客户端消息
2. 校验 messageId 幂等：msg:dedup:{messageId}
3. 校验发送者是否在会话中：session:members:{sessionId}
4. 生成房间消息序号：INCR msg:seq:{roomId}
5. 保存消息到数据库或消息存储
6. 记录投递状态
7. 查询接收方在线状态：user:online:{userId}
8. 在线：Pub/Sub 通知目标节点推 WebSocket
9. 离线：写入 offline:stream:{userId}
10. 客户端 ACK 后更新投递状态
11. 失败消息进入重试或死信流程
```

## 14. 生产注意事项

- Redis key 命名要统一小写和冒号分层，避免混乱。
- 大 Set / 大 Hash 不要全量读取，优先 `SSCAN` / `HSCAN`。
- Stream 必须有裁剪策略，但不能误删未消费消息。
- 本地缓存适合读多写少数据，不适合长时间缓存权限类数据。
- Redisson 看门狗只有不指定 leaseTime 时才自动续期。
- 密码不要写死在配置文件里，使用环境变量注入。
- 线上改序列化方式要谨慎，旧数据可能无法反序列化。
