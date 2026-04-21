package com.cl0.redisdemo.stage5;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 未读消息数管理服务 V2 (基于 Redis Hash 实现)
 * * 核心架构设计思路：
 * 为什么用 Hash 而不是 String？
 * 如果用 String，Key 会是 "unread:userA:sessionB" = 3。当你想知道 UserA 所有会话的未读状态时，
 * Redis 没有好办法一次性查出 UserA 的所有 Key（用 KEYS 命令会阻塞服务器）。
 * * 使用 Hash 结构：
 * Key (大 Key) = "unread:{userId}"  (代表这个用户)
 * HashKey (字段) = "{sessionId}"    (代表具体的聊天对象，比如好友ID或群ID)
 * HashValue (值) = 未读数量
 * * 这样，一个用户所有的未读会话都收拢在一个 Key 下，管理极其方便！
 */
@Service
public class UnreadCountServiceV2 {

    // Redis Key 的前缀，规范命名空间
    private static final String UNREAD_KEY_PREFIX = "unread:";

    private final StringRedisTemplate redisTemplate;

    public UnreadCountServiceV2(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 增加指定会话的未读数 (来新消息时调用)
     * 底层 Redis 命令：HINCRBY key field increment
     * * @param userId    当前用户的 ID（收件人）
     * @param sessionId 产生未读消息的会话 ID（发件人或群 ID）
     * @return 增加后的最新未读数
     */
    public Long increaseUnread(String userId, String sessionId) {
        // opsForHash().increment() 是原子操作。如果原本没有这个会话记录，会自动创建并从 0 开始加 1。
        return redisTemplate.opsForHash().increment(buildUnreadKey(userId), sessionId, 1L);
    }

    /**
     * 清除指定会话的未读数 (用户点进聊天窗口时调用)
     * 底层 Redis 命令：HDEL key field
     * * @param userId    当前用户的 ID
     * @param sessionId 被点开的会话 ID
     */
    public void clearUnread(String userId, String sessionId) {
        // 直接把这个会话的字段从 Hash 中删掉，代表 0 未读。这比存一个 "0" 更节省 Redis 内存。
        redisTemplate.opsForHash().delete(buildUnreadKey(userId), sessionId);
    }

    /**
     * 查询指定会话的未读数
     * 底层 Redis 命令：HGET key field
     * * @param userId    当前用户的 ID
     * @param sessionId 想查询的会话 ID
     * @return 未读数量
     */
    public Long getUnread(String userId, String sessionId) {
        Object value = redisTemplate.opsForHash().get(buildUnreadKey(userId), sessionId);
        if (value == null) {
            return 0L; // 如果查不到，说明没有未读消息
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 获取该用户【所有】有未读消息的会话列表
     * 底层 Redis 命令：HGETALL key
     * 业务场景：常用于渲染消息列表首页。返回的 Map 中，Key 是各个 sessionId，Value 是对应的未读数。
     * * @param userId 当前用户的 ID
     * @return 包含所有未读会话及数量的 Map
     */
    public Map<Object, Object> getAllUnread(String userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(buildUnreadKey(userId));
        // 防御性编程：如果没有未读记录，返回一个空 Map 而不是 null，防止前端或调用方报空指针异常
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }
        return entries;
    }

    /**
     * 获取该用户的【总计】未读消息数
     * 业务场景：常用于 APP 底部导航栏“消息”图标右上角的总计红点数字。
     * * @param userId 当前用户的 ID
     * @return 所有会话的未读数总和
     */
    public Long getTotalUnread(String userId) {
        // 先拿到所有的会话和未读数
        Map<Object, Object> entries = getAllUnread(userId);
        long total = 0L;

        // 在 Java 内存中遍历累加
        for (Object value : entries.values()) {
            if (value == null) {
                continue;
            }
            total += Long.parseLong(String.valueOf(value));
        }

        return total;
    }

    /**
     * 一键清除该用户的所有未读消息
     * 底层 Redis 命令：DEL key
     * 业务场景：用户点击“一键已读”功能时调用。
     * * @param userId 当前用户的 ID
     */
    public void clearAllUnread(String userId) {
        // 直接删掉这个大 Key，Hash 里面的所有小记录会随着大 Key 的销毁而全部清空
        redisTemplate.delete(buildUnreadKey(userId));
    }

    /**
     * 内部辅助方法：构建 Redis 大 Key
     */
    private String buildUnreadKey(String userId) {
        return UNREAD_KEY_PREFIX + userId;
    }
}