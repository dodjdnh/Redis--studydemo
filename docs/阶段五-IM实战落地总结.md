# 阶段五-IM实战落地总结

## 1. 本阶段目标

阶段五把前四个阶段的 Redis 能力组合成一套更接近 IM 项目的设计。

本阶段代码仍在：

```text
C:\Users\Kevin\Desktop\Redis进阶学习\redisdemo\src\main\java\com\cl0\redisdemo\stage5
```

设计文档：

```text
C:\Users\Kevin\Desktop\Redis进阶学习\redisdemo\src\main\java\com\cl0\redisdemo\stage5\IMRedisDesignDoc.md
```

## 2. 本阶段用到的命令 / 类 / 注解

| 命令 / 类 / 注解 | 是干什么的 |
|------------------|------------|
| `SET key value EX seconds` | 写入在线状态并设置 TTL |
| `EXPIRE` | 心跳续期在线状态 |
| `TTL` | 查看在线状态剩余过期时间 |
| `DEL` | 主动下线或清理缓存 |
| `INCR` | 生成房间内严格递增消息序号 |
| `HINCRBY` | 原子增加未读数 |
| `HGET` | 查询某个会话未读数 |
| `HGETALL` | 查询用户所有会话未读数 |
| `HDEL` | 清零某个会话未读数 |
| `SADD` | 添加房间成员 |
| `SREM` | 删除房间成员 |
| `SMEMBERS` | 读取房间成员，练习中使用；大房间生产要谨慎 |
| `PUBLISH` / `convertAndSend` | 发布房间广播消息 |
| `RedisMessageListenerContainer` | 订阅房间广播频道 |
| `MessageListenerAdapter` | 将 Redis Pub/Sub 消息转发到 Java 方法 |
| `@Value` | 从配置读取当前节点 ID，例如 `rtc.node-id` |

## 3. 本阶段代码对应关系

| 文件 | 解决的问题 | Redis 结构 |
|------|------------|------------|
| `OnlineStatusService` | 用户在线状态和心跳续期 | String + TTL |
| `MessageSeqGenerator` | 房间消息严格递增序号 | String + `INCR` |
| `UnreadCountServiceV2` | 用户按会话统计未读数 | Hash + `HINCRBY` |
| `RoomBroadcastService` | 房间成员广播、在线节点判断 | Set + String + Pub/Sub |
| `RoomBroadcastSubscriber` | 接收房间广播消息 | Pub/Sub listener |
| `RoomBroadcastConfig` | 配置房间广播订阅容器 | Redis listener container |
| `Stage5DemoController` | 提供阶段五验证接口 | HTTP |
| `IMRedisDesignDoc.md` | 沉淀 IM Redis Key 设计 | 文档 |

## 4. IM 场景对应关系

### 4.1 在线状态

Key：

```text
user:online:{userId}
```

Value：

```text
rtc-node-1
```

TTL：

```text
90 秒
```

用途：

```text
判断用户是否在线，以及用户连接在哪个 RTC 节点。
```

对应 RTC 项目：

```text
WebSocket 握手成功 -> markOnline
心跳 -> heartbeat
channelInactive / logout -> markOffline
```

### 4.2 消息序号

Key：

```text
msg:seq:{roomId}
```

命令：

```text
INCR msg:seq:{roomId}
```

用途：

```text
为房间内每条消息生成严格递增序号。
```

不要用时间戳或 ZSet score 替代严格消息序号。

### 4.3 未读数

Key：

```text
unread:{userId}
```

结构：

```text
field = sessionId
value = count
```

新消息：

```text
HINCRBY unread:{userId} {sessionId} 1
```

用户进入会话：

```text
HDEL unread:{userId} {sessionId}
```

### 4.4 房间广播

房间成员：

```text
room:members:{roomId}
```

在线状态：

```text
user:online:{userId}
```

广播频道：

```text
im:room:broadcast
```

流程：

```text
1. 当前节点收到房间消息
2. INCR 生成 seq
3. 读取房间成员
4. 查询每个成员在线节点
5. 在线成员发布 Pub/Sub 广播
6. 各 RTC 节点收到广播
7. 目标节点推本机 WebSocket
```

## 5. 与 RTC 项目现状的对比

| RTC 现状 | 问题 | 阶段五后的设计方向 |
|----------|------|--------------------|
| `ChannelManager` 只保存本机在线连接 | 多实例下无法知道用户是否在别的节点在线 | 增加 `user:online:{userId}` 保存所在节点 |
| 消息没有独立房间 seq 设计 | 高并发排序、补拉、ACK 对账困难 | 用 `INCR msg:seq:{roomId}` |
| 已有投递状态，但未读数未独立设计 | 投递成功不等于用户已读 | 增加 `unread:{userId}` Hash |
| 房间广播依赖本机 Channel | 跨节点用户收不到实时推送 | 用 Pub/Sub 做节点广播 |
| 离线消息主要靠快照和重试状态 | 可靠消费模型不完整 | 后续可用 `offline:stream:{userId}` |
| 手写重试锁 | 固定 TTL 可能提前过期 | 可升级 Redisson `tryLock` + 看门狗 |

## 6. 常见坑

### 6.1 在线状态不能只靠 JVM 内存

JVM 内存只能表示当前实例。

多 RTC 实例时，在线状态必须有 Redis 这类共享存储，否则会误判用户离线。

### 6.2 心跳续期失败要当成重要信号

`EXPIRE user:online:{userId}` 返回 false，通常表示 key 已不存在。

这说明 Redis 已认为用户离线，真实项目里要考虑让客户端重连或重新认证。

### 6.3 消息序号不要随便 reset

练习里提供了 reset 接口，方便测试。

真实 IM 系统里消息序号是游标的一部分，不应该随便重置。

### 6.4 未读数和投递状态不是一回事

投递状态表示：

```text
消息有没有送达、有没有 ACK。
```

未读数表示：

```text
用户有没有看过这个会话。
```

两者相关，但不能混为一个字段。

### 6.5 大房间不要频繁 `SMEMBERS`

阶段五练习为了直观用了 `SMEMBERS`。

生产中大房间应该考虑：

```text
SSCAN 分批
按在线节点维护成员索引
或者由业务服务提供分页成员列表
```

### 6.6 Pub/Sub 只是通知，不保证投递

Pub/Sub 订阅者离线时消息会丢。

房间广播如果要求离线补推，需要同时写 Stream 或持久化消息表。

### 6.7 targetNodeId 需要订阅方判断

Pub/Sub 是广播模型。

所有节点都会收到消息，只有：

```text
targetNodeId == currentNodeId
```

的节点才应该推本机 WebSocket。

## 7. 本阶段你现在应该掌握什么

你现在应该能把 Redis 能力组合成一条 IM 消息链路：

```text
WebSocket 连接成功：
    SET user:online:{userId} {nodeId} EX 90

客户端心跳：
    EXPIRE user:online:{userId} 90

发送房间消息：
    SETNX msg:dedup:{messageId}
    INCR msg:seq:{roomId}
    读取 room:members:{roomId}
    查询 user:online:{targetUserId}
    在线：Pub/Sub 广播到目标节点
    离线：写 offline:stream:{targetUserId}

新消息未读：
    HINCRBY unread:{userId} {sessionId} 1

用户进入会话：
    HDEL unread:{userId} {sessionId}
```

## 8. 阶段五产出

阶段五产出了：

```text
OnlineStatusService.java
MessageSeqGenerator.java
UnreadCountServiceV2.java
RoomBroadcastService.java
RoomBroadcastSubscriber.java
RoomBroadcastConfig.java
Stage5DemoController.java
IMRedisDesignDoc.md
```

## 9. 与最终回顾的衔接

阶段五已经把前四阶段串成了完整 IM Redis 设计。

下一步最终回顾会总结：

```text
阶段一：数据类型选型
阶段二：分布式锁和幂等
阶段三：缓存防护
阶段四：Pub/Sub 与 Stream
阶段五：IM 实战组合
```

并补一份学习路线之后的生产关注点。
