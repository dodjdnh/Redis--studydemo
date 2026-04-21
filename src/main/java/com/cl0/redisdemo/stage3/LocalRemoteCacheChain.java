package com.cl0.redisdemo.stage3;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 多级缓存联动类（二级缓存链实战示例）
 *
 * <p>
 * 本类展示了 L1（本地应用缓存） + L2（远程分布式缓存） 的经典多级缓存架构。
 * * 架构优势：
 * 1. L1 缓存（Caffeine）：基于 JVM 内存，速度极快（纳秒级），且没有网络 I/O 开销。用来抵抗极其恐怖的超高并发（例如双十一某件超级爆款商品的访问）。
 * 2. L2 缓存（Redis）：基于独立集群，容量大，多节点共享。用来作为第一级防线被击穿后的缓冲，同时保证分布式环境下的数据基准线。
 * 3. 兜底（Database）：MySQL 等持久化存储。
 * </p>
 */
@Service
public class LocalRemoteCacheChain {

    // ======================== 常量配置 ========================

    /** Redis 缓存 Key 前缀 */
    private static final String USER_INFO_KEY_PREFIX = "user:chain:";
    
    /** L2 远程缓存（Redis）的过期时间：30分钟 */
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);


    // ======================== 核心依赖 ========================

    private final StringRedisTemplate redisTemplate;
    
    /** * L1 本地缓存：使用有着 "本地缓存之王" 称号的 Caffeine。
     * 它的性能远超 Guava Cache，是目前 Spring Boot 推荐的默认本地缓存实现。
     */
    private final Cache<String, String> localCache;
    
    /** 模拟底层关系型数据库 */
    private final Map<String, String> fakeDatabase = new HashMap<>();


    /**
     * 构造函数：初始化各个缓存层和数据源
     */
    public LocalRemoteCacheChain(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        
        // 【关键配置】构建 Caffeine 本地缓存
        this.localCache = Caffeine.newBuilder()
                // 限制最大容量，防止内存溢出（OOM）。当达到 1000 时，会根据 W-TinyLFU 算法淘汰低频数据
                .maximumSize(1000)
                // L1 缓存的过期时间（1分钟）。
                // 为什么设置这么短？因为在分布式集群下，Node A 修改了数据清除了自己的本地缓存，
                // 但 Node B 是不知道的。设置 1 分钟可以保证即使出现不一致，最多也只忍受 1 分钟的脏数据。
                // (更高级的做法是引入 Redis Pub/Sub 或 RocketMQ 广播来主动失效全集群的本地缓存)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();

        // 初始化模拟数据库数据
        fakeDatabase.put("10001", "{\"userId\":\"10001\",\"nickname\":\"Kevin From DB\"}");
        fakeDatabase.put("10002", "{\"userId\":\"10002\",\"nickname\":\"Alice From DB\"}");
    }

    /**
     * 多级缓存查询主链路 (Read-Through 思想)
     *
     * @param userId 用户 ID
     * @return 查询结果及命中来源
     */
    public String getUserInfo(String userId) {
        // 第一步：先查 L1 本地缓存 (无网络 I/O，速度最快)
        String localValue = localCache.getIfPresent(userId);
        if (localValue != null) {
            return "Caffeine 本地缓存命中：" + localValue;
        }

        // 第二步：L1 未命中，去查 L2 远程缓存 (Redis)
        String redisKey = buildUserInfoKey(userId);
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue != null) {
            // L2 命中了，需要将数据【回写】到 L1 缓存，方便下次就近读取
            localCache.put(userId, redisValue);
            return "Redis 远程缓存命中，并写入 Caffeine：" + redisValue;
        }

        // 第三步：L2 也未命中，触发缓存穿透，去查数据库
        String dbValue = fakeDatabase.get(userId);
        if (dbValue == null) {
            return "数据库不存在该用户：" + userId;
            // 生产环境注意：这里同样应该考虑“防穿透”，写入 null 值到缓存中
        }

        // 第四步：数据库命中，将数据【回写】到 L2 和 L1 缓存
        redisTemplate.opsForValue().set(redisKey, dbValue, REDIS_TTL);
        localCache.put(userId, dbValue);

        return "数据库命中，并写入 Redis + Caffeine：" + dbValue;
    }

    /**
     * 多级缓存更新链路 (Cache-Aside 思想改进版)
     *
     * <p>策略：先更新数据库，再删除外部缓存，最后清理本地缓存。</p>
     */
    public String updateUserInfo(String userId, String nickname) {
        String newValue = "{\"userId\":\"" + userId + "\", \"nickname\":\"" + nickname + "\"}";

        // 1. 更新数据库
        fakeDatabase.put(userId, newValue);

        // 2. 删除 L2 远程缓存 (Redis)
        redisTemplate.delete(buildUserInfoKey(userId));

        // 3. 删除 L1 本地缓存 (当前 JVM 实例)
        localCache.invalidate(userId);

        return "已更新数据库，并删除 Redis 缓存和本地缓存：" + newValue;
    }

    /**
     * 辅助测试：主动清除本地缓存
     */
    public void clearLocalCache(String userId) {
        localCache.invalidate(userId);
    }

    /**
     * 辅助测试：主动清除远程缓存
     */
    public void clearRedisCache(String userId) {
        redisTemplate.delete(buildUserInfoKey(userId));
    }

    /**
     * 构建 Redis Key
     */
    private String buildUserInfoKey(String userId) {
        return USER_INFO_KEY_PREFIX + userId;
    }
}