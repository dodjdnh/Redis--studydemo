package com.cl0.redisdemo.stage3;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 热点击穿保护器（防止缓存击穿实战示例）
 *
 * <p>
 * 什么是“缓存击穿”？
 * 当一个高并发访问的“热点 Key”（例如秒杀商品、大V主页）突然过期的瞬间，
 * 会有海量的并发请求同时发现缓存失效，然后同时去查询数据库并尝试重建缓存，
 * 这瞬间的巨大并发极易将数据库打垮（DB 宕机）。
 *
 * <p>
 * 解决方案：互斥锁（Mutex Lock） + 双重检查（Double-Check）。
 * 保证在缓存失效的瞬间，只有一个线程能去查询数据库并重建缓存，其他线程要么等待，要么快速失败。
 * </p>
 */
@Service
public class HotspotCacheBreaker {

    // ======================== 常量配置 ========================

    /** 业务数据（热点用户信息）的 Redis Key 前缀 */
    private static final String USER_INFO_KEY_PREFIX = "user:info:hot:";
    
    /** 分布式锁的 Redis Key 前缀（非常重要：锁的粒度必须细化到具体的 userId，不能锁全局） */
    private static final String REBUILD_LOCK_PREFIX = "lock:rebuild:user:";
    
    /** 正常业务数据的缓存过期时间（30分钟） */
    private static final Duration USER_INFO_TTL = Duration.ofMinutes(30);


    // ======================== 核心依赖 ========================

    private final StringRedisTemplate redisTemplate;
    
    /** Redisson 客户端，用于实现分布式锁（比原生的 setnx 更安全，自带看门狗续期功能） */
    private final RedissonClient redissonClient;
    
    /** 模拟底层关系型数据库 */
    private final Map<String, String> fakeDatabase = new HashMap<>();


    /**
     * 构造函数：注入依赖并初始化模拟的热点数据
     */
    public HotspotCacheBreaker(StringRedisTemplate redisTemplate, RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;

        // 初始化底层数据库中的热点数据
        fakeDatabase.put("10001", "{\"userId\":\"10001\",\"nickname\":\"Hot Kevin\"}");
    }

    /**
     * 获取热点用户信息核心主流程（防击穿保护链路）
     *
     * @param userId 用户ID
     * @return 查询结果及来源描述
     */
    public String getHotUserInfo(String userId) {
        String key = buildUserInfoKey(userId);
        
        // 第一步：第一次查询缓存
        String cached = redisTemplate.opsForValue().get(key);

        // 如果缓存命中，直接返回，这是 99.9% 的正常情况
        if (cached != null) {
            return "Redis 缓存命中：" + cached;
        }

        // ======================================================================
        // 执行到这里，说明发生了【缓存未命中】。
        // 如果是高并发场景，此时可能有成千上万个线程同时到达这里。我们要限制它们打库。
        // ======================================================================

        // 获取该 userId 对应的分布式锁对象
        RLock lock = redissonClient.getLock(buildRebuildLockKey(userId));
        boolean locked = false;

        try {
            // 第二步：尝试获取分布式锁
            // tryLock(等待时间, 时间单位) -> 采用非阻塞或有限等待的方式。
            // 这里最多等 2 秒。如果拿不到锁就放弃，防止大量线程阻塞耗尽 Tomcat 线程池（雪崩效应）。
            locked = lock.tryLock(2, TimeUnit.SECONDS);

            // 【分支 A：没有抢到锁的线程】
            if (!locked) {
                // 没抢到锁，说明已经有其他线程正在查 DB 重建缓存了。
                // 此时可以稍微挣扎一下：再去查一次缓存，说不定那个抢到锁的线程刚刚把缓存写好了。
                String retryCached = redisTemplate.opsForValue().get(key);
                if (retryCached != null) {
                    return "没抢到锁，但稍后读到了别人重建的缓存：" + retryCached;
                }
                
                // 如果还是没查到，为了保护系统，直接降级返回提示，或者抛出特定异常让前端重试
                return "没抢到重建锁：系统繁忙请稍后重试，避免打爆数据库";
            }

            // 【分支 B：成功抢到锁的线程（The Chosen One）】
            // 第三步：拿到锁后，必须进行【双重检查（Double-Check）】
            // 为什么？因为你可能是在锁外面等了一会儿才抢到锁的（前面的线程释放了锁）。
            // 此时前面的线程很可能已经把缓存重建好了，如果你不检查直接查库，就又打穿一次 DB。
            String doubleCheckCached = redisTemplate.opsForValue().get(key);
            if (doubleCheckCached != null) {
                return "抢到锁后二次检查命中缓存：" + doubleCheckCached;
            }

            // 第四步：真的没有任何缓存，开始查询底层数据库
            // 这里模拟了一个耗时 3 秒的慢查询（真实业务中可能是复杂的关联查询或外部 RPC 调用）
            String dbValue = queryDatabaseSlowly(userId);
            
            if (dbValue == null) {
                return "数据库中不存在该用户：" + userId;
                // 思考：这里如果是真正的生产环境，应当结合上个例子的空值缓存策略，写入 __NULL__ 防止穿透。
            }

            // 第五步：将查到的数据重建回 Redis 缓存
            redisTemplate.opsForValue().set(key, dbValue, USER_INFO_TTL);
            return "抢到锁，查询数据库并重建缓存：" + dbValue;

        } catch (InterruptedException e) {
            // 恢复中断状态并快速失败
            Thread.currentThread().interrupt();
            return "等待重建锁时被中断";
        } finally {
            // 第六步：释放锁（极其重要）
            // 必须判断：1. 当前状态是锁定的； 2. 这个锁是当前线程自己加的（防止误删别人的锁）
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 模拟主动删除缓存（用于测试制造缓存失效的瞬间）
     */
    public void deleteCache(String userId) {
        redisTemplate.delete(buildUserInfoKey(userId));
    }

    /**
     * 模拟耗时的数据库查询（如复杂的联表、聚合计算）
     */
    private String queryDatabaseSlowly(String userId) {
        try {
            // 模拟 3 秒的慢查询耗时，这期间并发请求会被拦截在锁外面
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return fakeDatabase.get(userId);
    }

    /**
     * 构建业务数据的 Redis Key
     */
    private String buildUserInfoKey(String userId) {
        return USER_INFO_KEY_PREFIX + userId;
    }

    /**
     * 构建分布式互斥锁的 Redis Key
     */
    private String buildRebuildLockKey(String userId) {
        return REBUILD_LOCK_PREFIX + userId;
    }
}