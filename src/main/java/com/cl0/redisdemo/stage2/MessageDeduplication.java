package com.cl0.redisdemo.stage2;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 消息防重（去重）服务类。
 * 核心原理：利用 Redis 的 SETNX (setIfAbsent) 命令实现接口的【幂等性】。
 * 作用：确保同一个 MessageId 对应的消息，无论因为网络抖动或重试机制被发送了多少次，
 * 业务逻辑（如存入数据库、扣减积分等）永远只被执行一次。
 */
@Service
public class MessageDeduplication {

    // 去重标记在 Redis 中的统一前缀，方便管理和检索
    private static final String MESSAGE_DEDUP_KEY_PREFIX = "msg:dedup:";
    
    // 标记的过期时间：24 小时。
    // 为什么要设过期时间？如果不设，随着系统运行，Redis 会被海量的已处理消息 ID 撑爆内存。
    // 24 小时通常能覆盖绝大多数消息队列/网络的重试时间窗口。
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public MessageDeduplication(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 核心查重方法：判断这是不是该消息第一次来。
     * * @param messageId 全局唯一的消息 ID
     * @return true 代表是第一次处理；false 代表之前已经处理过了
     */
    public boolean markIfFirstTime(String messageId) {
        // 这里的 Value 填 "1" 或者任何字符串都可以，因为我们只关心 Key 存不存在
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                buildDedupKey(messageId),
                "1", 
                DEDUP_TTL
        );

        // 如果 setIfAbsent 返回 true，说明坑位是空的，我们成功插上了旗子，代表是“首次”。
        // 返回 false 则说明旗子已经在那里了，代表是“重复”。
        return Boolean.TRUE.equals(success);
    }

    /**
     * 模拟处理消息的业务入口
     */
    public String handleMessage(String messageId, String content) {
        // 1. 第一步永远是先查重（抢插旗权）
        boolean firstTime = markIfFirstTime(messageId);

        // 2. 如果不是第一次，直接丢弃（或返回成功信号欺骗上游，让上游不要再重试了）
        if (!firstTime) {
            return "重复消息：messageId=" + messageId + " 已处理过，本次跳过";
        }

        // 3. 确定是第一次，安全地执行后续的落库、推送等核心业务
        return "首次处理：messageId=" + messageId + ", content=" + content;
    }

    /**
     * 补偿机制：清除防重标记
     * 场景：如果在 handleMessage 内部执行后续业务时数据库宕机报错了，业务没执行成功。
     * 这时候你需要把 Redis 里的标记删掉，以便让上游再次重发时，系统能重新放行。
     */
    public void clearDedupMark(String messageId) {
        redisTemplate.delete(buildDedupKey(messageId));
    }

    /**
     * 内部辅助方法：规范化 Key 的生成策略
     */
    private String buildDedupKey(String messageId) {
        return MESSAGE_DEDUP_KEY_PREFIX + messageId;
    }
}