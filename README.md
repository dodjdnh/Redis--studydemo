# Redis IM Study Demo

这是一个面向 IM 场景的 Redis 进阶学习项目。

项目从 Redis 基础用法出发，围绕 `RealTimeCommunicationService` 里的真实 IM 问题，逐步练习数据类型选型、分布式锁、缓存防护、Pub/Sub、Stream，以及在线状态、未读数、房间广播等综合落地设计。

## 技术栈

- Java 17
- Spring Boot 3.5.13
- Spring Data Redis
- Redisson
- Caffeine
- Redis 7.x

## 项目结构

```text
src/main/java/com/cl0/redisdemo
├── stage1  数据类型精准选型
├── stage2  分布式锁进阶
├── stage3  缓存设计与陷阱
├── stage4  Pub/Sub 与 Stream
└── stage5  IM 实战落地

docs
├── 00-学习总览.md
├── 00b-项目现状分析.md
├── 阶段一-数据类型精准选型总结.md
├── 阶段二-分布式锁进阶总结.md
├── 阶段三-缓存设计与陷阱总结.md
├── 阶段四-PubSub与Stream总结.md
├── 阶段五-IM实战落地总结.md
└── 06-最终回顾.md
```

## 阶段内容

| 阶段 | 内容 | 重点 |
|------|------|------|
| 阶段一 | 数据类型精准选型 | String、Hash、List、Set、ZSet、INCR 在 IM 场景中的选择 |
| 阶段二 | 分布式锁进阶 | SET NX、Lua 安全释放、Redisson RLock、看门狗、消息幂等 |
| 阶段三 | 缓存设计与陷阱 | 缓存穿透、击穿、雪崩、Caffeine + Redis 两级缓存 |
| 阶段四 | Pub/Sub 与 Stream | 在线广播、离线消息、消费者组、ACK |
| 阶段五 | IM 实战落地 | 在线状态、消息序号、未读数、房间广播、Redis Key 设计文档 |

## Redis 配置

配置文件：

```text
src/main/resources/application.yml
```

默认连接：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 59000
      database: 0
      password: ${REDIS_PASSWORD:}
```

如果 Redis 有密码，在 PowerShell 中设置：

```powershell
$env:REDIS_PASSWORD="your_password"
```

如果 Redis 没有密码：

```powershell
Remove-Item Env:REDIS_PASSWORD -ErrorAction SilentlyContinue
```

## 启动项目

使用 VS Code 或 IDE 运行：

```text
com.cl0.redisdemo.RedisdemoApplication
```

默认访问地址：

```text
http://localhost:8080
```

如果要启动两个实例验证 Pub/Sub 跨节点广播，可以分别使用：

```text
--server.port=8080
--server.port=8081
```

两个实例需要连接同一个 Redis。

## 常用验证接口

### 阶段一

```text
GET /stage1/demo
```

验证 Hash、List、Set、ZSet、INCR、TTL 的基础 IM 场景。

### 阶段二

```text
GET /stage2/setnx-lock
GET /stage2/redisson-lock
GET /stage2/try-lock
GET /stage2/dedup?messageId=msg-100
GET /stage2/dedup/clear?messageId=msg-100
```

验证分布式锁、Redisson 看门狗、限时抢锁和消息幂等。

### 阶段三

```text
GET /stage3/user?userId=10001
GET /stage3/hot-user?userId=10001
GET /stage3/warmup/ttl
GET /stage3/chain/user?userId=10001
```

验证缓存穿透、击穿、雪崩和两级缓存。

### 阶段四

```text
GET /stage4/pubsub/send?targetUserId=10002&content=hello
GET /stage4/pubsub/received
GET /stage4/stream/add?userId=10002&messageId=msg-1&content=hello
GET /stage4/stream/read?userId=10002&count=10
GET /stage4/compare?userId=10002&content=compare-message
```

验证 Pub/Sub 在线广播、Stream 持久化写入、消费者组读取和 ACK。

### 阶段五

```text
GET /stage5/online?userId=10001&nodeId=rtc-node-1
GET /stage5/heartbeat?userId=10001
GET /stage5/seq/next?roomId=room-2001
GET /stage5/unread/increase?userId=10002&sessionId=room-2001
GET /stage5/room/member/add?roomId=room-2001&userId=10002
GET /stage5/room/broadcast?roomId=room-2001&senderUserId=10001&content=hello
```

验证在线状态、消息序号、未读数、房间成员和房间广播。

## 文档入口

建议先阅读：

```text
docs/00-学习总览.md
docs/00b-项目现状分析.md
docs/06-最终回顾.md
```

每个阶段的详细总结在 `docs` 目录中。

## 说明

本项目是学习型 Demo，重点是理解 Redis 在 IM 场景下的设计取舍。

生产环境还需要继续补充：

- Redis Cluster
- Lua 原子脚本
- Stream pending 消息重试
- 更完整的消息存储
- 监控和告警
- 权限类缓存一致性处理
