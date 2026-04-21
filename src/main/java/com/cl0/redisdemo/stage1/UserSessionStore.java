package com.cl0.redisdemo.stage1;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户会话存储服务
 * 使用 Redis Hash 类型存储，Key 为会话 Token，Field 存储用户多维度信息
 */
@Service
public class UserSessionStore {

    // 会话 Key 的前缀，方便在 Redis 中根据业务分类
    private static final String SESSION_KEY_PREFIX = "session:";
    
    // 会话过期时间，IM 系统通常需要心跳维持，超时未活跃则自动失效
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    public UserSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建用户会话
     * 这里将用户 ID、设备、节点 ID 等多个字段打包存入一个 Hash 中
     *
     * @param token     用户的访问令牌
     * @param userId    用户唯一标识
     * @param device    设备类型 (e.g., Android, Web)
     * @param nodeId    Netty 节点 ID (用于多节点推送时精确定位用户在哪台服务器上)
     * @param channelId 通道 ID (Netty 内部的管道标识)
     */
    public void createSession(String token, String userId, String device, String nodeId, String channelId) {
        String key = buildSessionKey(token);
        String now = OffsetDateTime.now().toString();

        // 构造 Hash 的多个 Field
        Map<String, String> sessionFields = new HashMap<>();
        sessionFields.put("userId", userId);
        sessionFields.put("device", device);
        sessionFields.put("nodeId", nodeId);
        sessionFields.put("channelId", channelId);
        sessionFields.put("loginAt", now);        // 记录登录时间
        sessionFields.put("lastActiveAt", now);   // 初始活跃时间

        // 1. 使用 HMSET (putAll) 一次性将 Map 存入 Redis Hash
        redisTemplate.opsForHash().putAll(key, sessionFields);
        
        // 2. 为整个 Hash Key 设置过期时间
        redisTemplate.expire(key, SESSION_TTL);
    }

    /**
     * 获取会话详情
     * 对应命令：HGETALL session:{token}
     *
     * @return 返回包含所有字段的 Map
     */
    public Map<Object, Object> getSession(String token) {
        return redisTemplate.opsForHash().entries(buildSessionKey(token));
    }

    /**
     * 刷新用户心跳
     * 这里体现了 Hash 的优势：只需更新单个 Field (lastActiveAt)，而无需操作 userId 等不变字段
     *
     * @param token 用户的访问令牌
     */
    public void refreshHeartbeat(String token) {
        String key = buildSessionKey(token);

        // 1. 局部更新：只更新最后活跃时间
        redisTemplate.opsForHash().put(key, "lastActiveAt", OffsetDateTime.now().toString());
        
        // 2. 续期：重置过期时间
        redisTemplate.expire(key, SESSION_TTL);
    }

    /**
     * 删除会话 (用户登出)
     * 对应命令：DEL session:{token}
     */
    public void deleteSession(String token) {
        redisTemplate.delete(buildSessionKey(token));
    }

    /**
     * 构建 Redis Key
     */
    private String buildSessionKey(String token) {
        return SESSION_KEY_PREFIX + token;
    }
}