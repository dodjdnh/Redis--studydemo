package com.cl0.redisdemo.stage5;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户在线状态管理服务
 * 利用 Redis 的 String 结构和过期时间（TTL）来实现用户在线检测和节点追踪
 */
@Service
public class OnlineStatusService {

    // Redis Key 的前缀，方便在 Redis Desktop Manager 等工具中分组查看
    private static final String ONLINE_KEY_PREFIX = "user:online:";
    
    // 在线状态的有效期。如果 90 秒内没有心跳，Redis 会自动删除该 Key，视用户为离线
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数注入
     */
    public OnlineStatusService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 标记用户为在线状态
     * * @param userId 用户唯一标识
     * @param nodeId 关键点：记录用户当前连接的服务器节点 ID。
     * 在分布式架构中，这能帮助我们知道该把消息推送到哪台服务器。
     */
    public void markOnline(String userId, String nodeId) {
        // 设置 Key 的值为 nodeId，并同步设置过期时间
        redisTemplate.opsForValue().set(buildOnlineKey(userId), nodeId, ONLINE_TTL);
    }

    /**
     * 用户心跳续期
     * 客户端定期发送请求（如每 30 秒一次），证明自己还活着
     * * @return true 表示续期成功（用户还在连接中）；false 表示 Key 已消失（用户可能已过期离线）
     */
    public boolean heartbeat(String userId) {
        String key = buildOnlineKey(userId);
        // 仅仅重置过期时间，不修改已经存储的内容（nodeId）
        Boolean renewed = redisTemplate.expire(key, ONLINE_TTL);
        return Boolean.TRUE.equals(renewed);
    }

    /**
     * 主动标记用户为下线
     * 当用户点击“退出登录”或 WebSocket 连接正常断开时调用
     */
    public void markOffline(String userId) {
        // 直接从 Redis 中删除该 Key
        redisTemplate.delete(buildOnlineKey(userId));
    }

    /**
     * 判断用户当前是否在线
     */
    public boolean isOnline(String userId) {
        // 只要 Redis 里能查到值，就代表在线
        return getOnlineNode(userId) != null;
    }

    /**
     * 获取用户当前所在的服务器节点
     * 常用于消息路由：要发消息给用户 A，先查 A 在哪台机器上
     * * @return 返回服务器节点 ID，如果离线则返回 null
     */
    public String getOnlineNode(String userId) {
        return redisTemplate.opsForValue().get(buildOnlineKey(userId));
    }

    /**
     * 查询离自动离线还有多少秒
     * 主要用于调试或监控：查看该用户如果不发心跳，还有多久会被系统踢下线
     * * @return 剩余秒数
     */
    public Long getOnlineTtlSeconds(String userId) {
        return redisTemplate.getExpire(buildOnlineKey(userId));
    }

    /**
     * 构建 Redis 内部存储的完整 Key 名
     * 示例：user:online:10001
     */
    private String buildOnlineKey(String userId) {
        return ONLINE_KEY_PREFIX + userId;
    }
}