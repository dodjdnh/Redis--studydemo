package com.cl0.redisdemo.stage4;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 发布/订阅 (Pub/Sub) 模式的消息发送服务。
 * 该类的主要职责是将用户的即时通讯 (IM) 消息推送到指定的 Redis 频道中。
 */
@Service
public class PubSubMessenger {

    /**
     * 定义消息推送的 Redis 频道 (Topic) 名称。
     * 所有订阅了 "im:push" 频道的客户端或微服务节点，都会接收到发送至此频道的消息。
     */
    public static final String PUSH_CHANNEL = "im:push";

    /**
     * Spring Data Redis 提供的核心类，用于执行 Redis 的字符串相关操作。
     * 声明为 final 确保其不可变性。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造器注入 (Constructor Injection)。
     * 推荐使用这种方式注入 Spring Bean，而不是使用 @Autowired 字段注入。
     * 它可以保证依赖在对象实例化时就准备好，并且更利于编写单元测试。
     *
     * @param redisTemplate Redis 字符串操作模板
     */
    public PubSubMessenger(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将推送消息发布到 Redis 频道。
     *
     * @param targetUserId 目标接收用户的 ID
     * @param content      需要推送的具体消息内容
     * @return 成功接收到该消息的订阅者（客户端/节点）数量。
     * 如果返回 0，通常意味着当前没有节点订阅该频道。
     */
    public Long publishPushMessage(String targetUserId, String content) {
        // 1. 构建 JSON 格式的消息字符串
        // 这里使用了原生的字符串拼接方式。
        String messageJson = "{\"targetUserId\":\"" + targetUserId + "\",\"content\":\"" + content + "\"}";
        
        // 2. 通过 Redis 模板的 convertAndSend 方法发布消息
        // 第一个参数是频道名称，第二个参数是实际的消息内容。
        return redisTemplate.convertAndSend(PUSH_CHANNEL, messageJson);
    }
}