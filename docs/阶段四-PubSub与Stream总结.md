# 阶段四-PubSub与Stream总结

## 1. 本阶段目标

阶段四解决的是：IM 消息在多 RTC 实例下如何实时通知，以及离线消息如何可靠保存和消费。

本阶段核心结论：

```text
Pub/Sub 负责在线实时通知。
Stream 负责持久化消息和可靠消费。
```

练习代码位置：

```text
C:\Users\Kevin\Desktop\Redis进阶学习\redisdemo\src\main\java\com\cl0\redisdemo\stage4
```

## 2. 本阶段用到的命令 / 类 / 注解

| 命令 / 类 / 注解 | 是干什么的 |
|------------------|------------|
| `PUBLISH` | 向 Redis Pub/Sub 频道发布消息 |
| `SUBSCRIBE` | 订阅 Redis Pub/Sub 频道 |
| `convertAndSend` | Spring 发布 Pub/Sub 消息的方法 |
| `RedisMessageListenerContainer` | Spring 的 Redis 订阅监听容器 |
| `MessageListenerAdapter` | 把 Redis 消息转发到指定 Java 方法 |
| `ChannelTopic` | 表示一个 Redis Pub/Sub 频道 |
| `XADD` | 向 Redis Stream 追加一条消息 |
| `XLEN` | 查看 Stream 长度 |
| `XRANGE` | 按 ID 范围查看 Stream 消息 |
| `XGROUP CREATE` | 创建 Stream 消费者组 |
| `XREADGROUP` | 用消费者组读取 Stream 消息 |
| `XACK` | 确认 Stream 消息处理成功 |
| `XPENDING` | 查看消费者组里已投递但未确认的消息 |
| `XTRIM` | 裁剪 Stream 长度，避免无限增长 |
| `StreamRecords` | Spring 创建 Stream 消息记录的工具 |
| `MapRecord` | Spring 表示 Stream 一条消息的对象 |
| `RecordId` | Stream 写入后返回的消息 ID |
| `Consumer` | Stream 消费者组里的消费者标识 |
| `ReadOffset.lastConsumed()` | 从消费者组上次消费位置之后读取新消息 |
| `StreamOffset` | 指定读取哪个 Stream 和哪个位置 |

## 3. 本阶段代码对应关系

| 文件 | 练习内容 | 解决的问题 |
|------|----------|------------|
| `PubSubMessenger` | 发布 Pub/Sub 消息 | 在线消息跨 RTC 节点通知 |
| `PubSubMessageSubscriber` | 接收 Pub/Sub 消息 | 模拟 RTC 节点收到广播后本地处理 |
| `PubSubConfig` | 配置 Redis 频道订阅 | 启动后自动订阅 `im:push` |
| `StreamProducer` | 用 `XADD` 写入 Stream | 保存离线消息或可靠任务 |
| `StreamConsumer` | 消费者组读取 + `XACK` | 消息处理成功后确认，避免取出即丢 |
| `StreamVsPubSubDemo` | 对比 Pub/Sub 和 Stream 行为 | 直观看到 Pub/Sub 不留历史，Stream 会保存 |
| `Stage4DemoController` | 提供 HTTP 验证接口 | 浏览器验证发布、接收、写入、消费 |

## 4. Pub/Sub 和 Stream 的核心区别

| 对比点 | Pub/Sub | Stream |
|--------|---------|--------|
| 是否持久化 | 不持久化 | 持久化 |
| 订阅者离线时 | 消息直接丢失 | 消息仍保存在 Stream |
| 是否支持 ACK | 不支持 | 支持 `XACK` |
| 是否支持消费者组 | 不支持 | 支持 |
| 适合场景 | 在线通知、节点广播、缓存删除通知 | 离线消息、可靠任务、失败补偿 |
| IM 中的定位 | 通知在线 RTC 节点推 WebSocket | 保存离线消息，上线后补推 |

一句话：

```text
Pub/Sub 负责“现在通知”。
Stream 负责“保存下来，后面还能处理”。
```

## 5. IM 场景对应关系

### 5.1 在线消息跨节点推送

当前 RTC 项目用 `ChannelManager` 保存本机连接：

```text
userId -> Channel
```

单实例没问题，多实例会出现：

```text
用户 B 连接 RTC-2
消息请求打到 RTC-1
RTC-1 本机查不到 B
误判 B 离线
```

引入 Pub/Sub 后：

```text
RTC-1 发布 im:push
RTC-1 / RTC-2 / RTC-3 都收到
RTC-2 发现 B 在本机
RTC-2 推 WebSocket
其他节点忽略
```

### 5.2 离线消息可靠补推

用户不在线时，不能只发 Pub/Sub。

应该写入 Stream：

```text
offline:stream:{userId}
```

用户上线后：

```text
XREADGROUP 读取离线消息
推 WebSocket
成功后 XACK
失败则不 ACK，留在 pending
```

### 5.3 失败重试和死信

RTC 项目现在已经有失败重试和死信统计。

Stream 可以进一步承接：

```text
失败投递任务
补偿任务
死信再处理任务
```

消费者未 ACK 的消息可以通过 pending 列表追踪，而不是像 List 那样取出即删除。

## 6. 与 RTC 项目现状的对比

| RTC 现状 | 风险 | 阶段四后的改进方向 |
|----------|------|--------------------|
| `ChannelManager` 只保存本机 Channel | 多实例时跨节点不可见 | 用 Pub/Sub 广播在线推送事件 |
| 用户不在本机就可能被判断为离线 | 用户实际在线但在其他节点 | 收到 Pub/Sub 后每个节点本地判断是否持有 Channel |
| 当前没有 Pub/Sub | 节点之间无法实时通知 | 增加 `im:push` 频道 |
| 当前没有 Stream | 离线可靠队列能力不足 | 增加 `offline:stream:{userId}` |
| 当前有消息快照和投递状态 | 能重试，但不是标准消费者组模型 | 可用 Stream 消费者组增强可靠消费和 pending 追踪 |

## 7. 常见坑

### 7.1 Pub/Sub 不是离线消息方案

Pub/Sub 不保存历史。

订阅者不在线时：

```text
消息直接丢。
```

所以不能用 Pub/Sub 保存离线消息。

### 7.2 Pub/Sub 返回的订阅者数量不是用户投递成功数

`convertAndSend` 返回值表示：

```text
有几个 Redis 订阅连接收到消息。
```

不表示：

```text
有几个用户 WebSocket 推送成功。
```

WebSocket 是否写成功、客户端是否 ACK，还要业务层自己处理。

### 7.3 Stream 的 `XACK` 不会删除消息

`XACK` 只是告诉消费者组：

```text
这条消息处理完成，不再 pending。
```

Stream 里的历史消息仍然存在。

如果要控制长度，需要 `XTRIM` 或 `XDEL`，但要谨慎，避免删掉其他消费者组还没处理的消息。

### 7.4 List 和 Stream 的可靠性不同

List 的 `RPOP`：

```text
取出即删除。
```

如果服务取出后崩溃，消息就丢。

Stream 的消费者组：

```text
读到不等于完成。
ACK 后才算完成。
```

### 7.5 消费者组名称和消费者名称要设计清楚

消费者组代表一类消费逻辑。

消费者名称代表具体实例。

真实多实例里不应该所有实例都叫：

```text
rtc-consumer-1
```

而应该区分：

```text
rtc-node-1
rtc-node-2
rtc-node-3
```

### 7.6 Stream 裁剪不能随便做

`XTRIM` 可以防止 Stream 无限增长，但如果裁剪了未消费或未 ACK 的消息，会造成数据丢失。

生产里要结合：

```text
保留时间
最大长度
pending 状态
业务补偿能力
```

综合设计。

### 7.7 启动多个实例时只改 Web 端口，不改 Redis 端口

两个 redisdemo 实例应该是：

```text
server.port=8080
server.port=8081
```

但 Redis 仍然连接同一个：

```text
localhost:59000
```

不要把 Redis 端口当成 Spring Boot 端口修改。

## 8. 本阶段你现在应该掌握什么

你现在应该能说清楚：

```text
Pub/Sub：实时广播，不持久化，适合在线通知。
Stream：持久化消息流，支持消费者组和 ACK，适合可靠投递。
```

也应该能设计出 IM 消息链路：

```text
用户在线：
    写投递状态
    Pub/Sub 广播推送事件
    持有本机 Channel 的节点推 WebSocket
    客户端 ACK 后更新投递状态

用户离线：
    写 Stream 保存离线消息
    用户上线后 XREADGROUP 补推
    推送成功后 XACK
```

## 9. 与下一阶段的衔接

阶段五会把前四个阶段整合成 IM 实战设计：

```text
在线状态：String + TTL 心跳
消息序号：INCR
未读数：Hash + HINCRBY
房间广播：Pub/Sub
离线补推：Stream
幂等控制：msg:dedup:{messageId}
缓存保护：穿透 / 击穿 / 雪崩
```

下一阶段重点不是单个 Redis 命令，而是把它们组合成一套更完整的 RTC Redis 设计。
