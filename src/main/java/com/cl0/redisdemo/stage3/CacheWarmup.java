package com.cl0.redisdemo.stage3;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存预热与防雪崩加载器
 *
 * <p>
 * 核心功能：
 * 1. 缓存预热 (Cache Warmup)：在系统启动时（或通过定时任务/运营接口主动触发），
 * 提前将极大概率会被高频访问的“热点数据”加载进 Redis 中。避免冷启动时，
 * 第一波海量并发请求直接打穿毫无防备的底层数据库。
 * 2. 防缓存雪崩 (Cache Avalanche Prevention)：在批量写入缓存时，为每个 Key 设置
 * 基础过期时间，并附加一个“随机抖动值（Jitter）”，打散数据的过期时间点。
 * </p>
 */
@Service
public class CacheWarmup {

    // ======================== 常量配置 ========================

    /** 预热数据的 Redis Key 前缀 */
    private static final String USER_INFO_KEY_PREFIX = "user:warm:";
    
    /** 基础的缓存过期时间（30分钟） */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);
    
    /** * 最大随机抖动时间（300秒，即 5 分钟）
     * 作用：让这批预热的数据在 30 ~ 35 分钟之内“陆续”过期，而不是在第 30 分钟时“同时”过期。
     */
    private static final long MAX_JITTER_SECONDS = 300L;


    // ======================== 核心依赖 ========================

    private final StringRedisTemplate redisTemplate;
    
    /** * 模拟待预热的热点用户数据集合 
     * (使用 LinkedHashMap 只是为了在遍历时保持我们添加的顺序，方便观察结果)
     */
    private final Map<String, String> hotUsers = new LinkedHashMap<>();


    /**
     * 构造函数：注入依赖并初始化热点数据
     */
    public CacheWarmup(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        // 模拟从大数据分析平台或定时报表中拉取到的“最活跃热点用户”数据
        // 实际生产中，这里通常是批量查询数据库（如查出粉丝数 Top 1000 的大V）
        hotUsers.put("10001", "{\"userId\":\"10001\",\"nickname\":\"System Bot\"}");
        hotUsers.put("10002", "{\"userId\":\"10002\",\"nickname\":\"Support\"}");
        hotUsers.put("10003", "{\"userId\":\"10003\",\"nickname\":\"Group Owner\"}");
    }

    /**
     * 监听 Spring 容器启动完成事件，自动执行缓存预热
     *
     * <p>
     * 使用 @PostConstruct 注解，意味着当 Spring Boot 启动，这个 Bean 初始化完成后，
     * 就会立刻自动执行本方法。确保应用对外提供服务前，缓存已经准备就绪。
     * </p>
     */
    @PostConstruct
    public void warmupOnStartup() {
        warmupHotUsers();
        // 生产建议：预热过程如果涉及到大量数据，可以另起线程异步执行，以免阻塞应用启动
    }

    /**
     * 核心预热逻辑（暴露为 public 是为了允许通过外部 HTTP 接口或定时任务再次手动触发）
     *
     * @return 返回每个 userId 对应的真实过期时间（单位：秒），方便控制台或接口观察随机抖动效果
     */
    public Map<String, Long> warmupHotUsers() {
        Map<String, Long> ttlSecondsByUser = new LinkedHashMap<>();

        // 遍历所有的热点数据，逐个加载到 Redis
        for (Map.Entry<String, String> entry : hotUsers.entrySet()) {
            String userId = entry.getKey();
            String userJson = entry.getValue();
            
            // 【关键步骤】获取带有随机抖动的过期时间
            Duration ttl = buildTtlWithJitter();

            // 写入 Redis
            redisTemplate.opsForValue().set(buildUserInfoKey(userId), userJson, ttl);
            
            // 记录生成的 TTL 用于返回观察
            ttlSecondsByUser.put(userId, ttl.getSeconds());
        }

        return ttlSecondsByUser;
    }

    /**
     * 获取指定用户的剩余过期时间（主要用于外部接口测试和验证）
     *
     * @param userId 用户 ID
     * @return 剩余存活时间（秒）
     */
    public Long getTtlSeconds(String userId) {
        return redisTemplate.getExpire(buildUserInfoKey(userId));
    }

    /**
     * 构建带有随机抖动（Jitter）的过期时间【防雪崩核心机制】
     *
     * <p>
     * 什么是缓存雪崩？
     * 如果我们对所有预热数据都设置绝对一致的过期时间（例如都是 30 分钟），
     * 那么在 30 分钟后的那一瞬间，成千上万的热点缓存会【同时失效】。
     * 这时如果有海量请求打过来，都会发现缓存没有命中，从而瞬间全部涌向数据库，造成数据库雪崩宕机。
     * * 解决方案：在 Base TTL 的基础上，加一个随机秒数，把失效时间打散。
     * </p>
     *
     * @return 最终的过期时间（例如 30分02秒、34分15秒 等）
     */
    private Duration buildTtlWithJitter() {
        // 使用 ThreadLocalRandom 线程安全地生成 0 到 MAX_JITTER_SECONDS 之间的随机长整数
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, MAX_JITTER_SECONDS + 1);
        
        // 基础时间 + 随机秒数
        return BASE_TTL.plusSeconds(jitterSeconds);
    }

    /**
     * 构建预热数据的 Redis Key
     */
    private String buildUserInfoKey(String userId) {
        return USER_INFO_KEY_PREFIX + userId;
    }
}