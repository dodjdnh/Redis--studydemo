package com.cl0.redisdemo.stage4;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 这是一个用于对比 Redis 两种常见消息模型（Pub/Sub 与 Stream）的服务类。
 * 核心目的：向开发者直观展示“即时广播（不持久化）”与“可靠消息队列（持久化）”的区别。
 */
@Service
public class StreamVsPubSubDemo {

    // 处理 Pub/Sub（发布/订阅）模式的依赖组件
    private final PubSubMessenger pubSubMessenger;
    // 处理 Stream（数据流/消息队列）模式的依赖组件
    private final StreamProducer streamProducer;

    /**
     * 构造函数注入（Spring Boot 推荐的依赖注入方式，保证核心组件在实例化时不可为空）
     */
    public StreamVsPubSubDemo(PubSubMessenger pubSubMessenger,
                              StreamProducer streamProducer) {
        this.pubSubMessenger = pubSubMessenger;
        this.streamProducer = streamProducer;
    }

    /**
     * 核心对比方法：同时向 Pub/Sub 和 Stream 发送相同的消息，并向前端返回两者执行结果的差异。
     *
     * @param userId  目标用户 ID
     * @param content 消息的具体内容
     * @return 包含两种模式执行结果对比数据的 Map
     */
    public Map<String, Object> compare(String userId, String content) {
        // 1. 生成一个全局唯一的业务消息 ID，用于追踪同一条消息在不同机制下的流转
        String messageId = "msg-" + UUID.randomUUID();

        // ==========================================
        // 模式 A：Redis Pub/Sub (发布/订阅) 测试
        // ==========================================
        // 发布消息。Pub/Sub 的特点是“阅后即焚”，Redis 内存中根本不会保存这条消息。
        // 它底层的返回值，仅仅代表在执行 publish 命令的【那一瞬间】，有多少个客户端正处于在线且订阅了该频道的状态。
        // 就像收音机广播一样，如果用户没开机（离线），这条消息对他来说就彻底丢失了。
        Long subscriberCount = pubSubMessenger.publishPushMessage(userId, content);

        // ==========================================
        // 模式 B：Redis Stream (持久化消息流) 测试
        // ==========================================
        // 追加消息到 Stream。Stream 具有“持久化”特性，是正经的消息队列。
        // 消息发送过去后，就会老老实实存在 Redis 硬盘/内存里，类似于邮箱里的邮件。
        // 它的返回值不是订阅人数，而是 Redis Stream 自动生成的物理数据记录 ID（例如：1681234567890-0）。
        String streamRecordId = streamProducer.addOfflineMessage(userId, messageId, content);

        // 查询当前该用户的专属 Stream 队列中，总共堆积了多少条未被清理的消息。
        Long streamSize = streamProducer.streamSize(userId);

        // ==========================================
        // 组装对比结果返回给调用方 (使用 LinkedHashMap 保证 JSON 输出顺序与代码 put 顺序一致)
        // ==========================================
        Map<String, Object> result = new LinkedHashMap<>();
        
        // 基础信息
        result.put("messageId", messageId);

        // Pub/Sub 的结果表现
        result.put("pubSubChannel", PubSubMessenger.PUSH_CHANNEL); // 广播投递的频道名称
        result.put("pubSubSubscriberCount", subscriberCount);      // 瞬间在线收听的人数
        result.put("pubSubMeaning", "只代表发布瞬间有多少订阅者在线，Redis 不保存这条 Pub/Sub 历史消息");

        // Stream 的结果表现
        result.put("streamKey", "offline:stream:" + userId);       // 该用户的专属 Stream 键名
        result.put("streamRecordId", streamRecordId);              // Redis 生成的底层消息 ID
        result.put("streamSize", streamSize);                      // 队列目前的总长度
        result.put("streamMeaning", "Stream 会保存消息，后续可以用消费者组读取并 ACK");

        return result;
    }
}