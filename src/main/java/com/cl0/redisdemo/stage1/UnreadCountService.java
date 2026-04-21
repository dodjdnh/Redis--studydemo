package com.cl0.redisdemo.stage1;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 未读消息数统计服务
 * 使用 Redis Hash 类型实现。
 * Key 对应用户，Field 对应会话(单聊/群聊)，Value 对应未读消息数量。
 */
@Service
public class UnreadCountService {

    // 未读消息数的 Key 前缀，例如 "unread:1001"
    private static final String UNREAD_KEY_PREFIX = "unread:";

    private final StringRedisTemplate redisTemplate;

    public UnreadCountService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 增加未读数 (收到新消息时)
     * 对应 Redis 命令: HINCRBY key field 1
     * 业务场景: 当 B 不在线（或没打开当前聊天窗口）时，A 给 B 发了一条消息，B 对 A 的未读数 +1。
     *
     * @param userId    接收方用户 ID (邮箱的主人)
     * @param sessionId 发送方 ID 或群组 ID (发件人)
     * @return 增加后的最新未读数
     */
    public Long increaseUnread(String userId, String sessionId) {
        // 原子操作，如果 sessionId 字段不存在，Redis 会自动将其初始化为 0 再加 1。
        return redisTemplate.opsForHash().increment(buildUnreadKey(userId), sessionId, 1L);
    }

    /**
     * 减少未读数 (通常用于前端逐条阅读的场景，虽然 IM 中更常用的是一键清空)
     * 对应 Redis 命令: HINCRBY key field -1 + HDEL
     *
     * @param userId    接收方用户 ID
     * @param sessionId 会话 ID
     * @return 减少后的未读数
     */
    public Long decreaseUnread(String userId, String sessionId) {
        Long current = getUnread(userId, sessionId);
        if (current <= 0) {
            return 0L; // 已经是 0 了，防止出现负数未读
        }

        // 步长传负数，相当于做减法
        Long next = redisTemplate.opsForHash().increment(buildUnreadKey(userId), sessionId, -1L);
        
        // 【核心优化】：如果未读数扣减到 0 了，直接把这个字段从 Hash 中删掉！
        // 这样可以防止 Redis 里堆积大量毫无意义的 "friend:1 -> 0", "group:8 -> 0" 数据。
        if (next != null && next <= 0) {
            redisTemplate.opsForHash().delete(buildUnreadKey(userId), sessionId);
            return 0L;
        }
        return next;
    }

    /**
     * 获取单个会话的未读数
     * 对应 Redis 命令: HGET
     * 业务场景: 前端渲染聊天列表时，某个聊天框右上角的红点数字。
     */
    public Long getUnread(String userId, String sessionId) {
        Object value = redisTemplate.opsForHash().get(buildUnreadKey(userId), sessionId);
        // 如果 Redis 里没有这个字段，说明没收到过消息，或者未读数已经被清零(删掉)了，直接返回 0。
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 清零某个会话的未读数 (点开聊天窗口时触发)
     * 对应 Redis 命令: HDEL (注意这里不是 HSET 为 0，而是直接删除)
     * 业务场景: 用户在列表中点击了某个头像进入聊天室，该会话红点消失。
     */
    public void clearUnread(String userId, String sessionId) {
        // 【核心优化】：用删除代替 HSET 设为 0，极大节省 Redis 内存。
        redisTemplate.opsForHash().delete(buildUnreadKey(userId), sessionId);
    }

    /**
     * 获取用户所有会话的未读数
     * 对应 Redis 命令: HGETALL
     * 业务场景: 用户刚登录 App 时，需要一次性拉取所有红点数据，或者计算底部 Tab 栏的“总未读数”。
     */
    public Map<Object, Object> getAllUnread(String userId) {
        // entries() 就是 HGETALL，一次性把 Hash 里的所有键值对全部拿出来。
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(buildUnreadKey(userId));
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }
        return entries;
    }

    private String buildUnreadKey(String userId) {
        return UNREAD_KEY_PREFIX + userId;
    }
}