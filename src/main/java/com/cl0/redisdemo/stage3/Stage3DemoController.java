package com.cl0.redisdemo.stage3;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 3 终极综合演示控制器：Redis 高可用防线与极致性能架构
 *
 * <p>
 * 本类整合了企业级高并发场景下的四大核心组件：
 * 1. BloomFilterGuard：布隆过滤器 + 空值拦截，解决【缓存穿透】
 * 2. HotspotCacheBreaker：分布式锁 + 双重检查，解决【缓存击穿】
 * 3. CacheWarmup：主动加载 + TTL 随机抖动，解决【缓存雪崩】
 * 4. LocalRemoteCacheChain：Caffeine (L1) + Redis (L2)，解决【超高并发与网络瓶颈】
 * </p>
 */
@RestController
public class Stage3DemoController {

    private final BloomFilterGuard bloomFilterGuard;
    private final HotspotCacheBreaker hotspotCacheBreaker;
    private final CacheWarmup cacheWarmup;
    private final LocalRemoteCacheChain localRemoteCacheChain;

    /**
     * 构造函数：注入所有核心组件
     */
    public Stage3DemoController(BloomFilterGuard bloomFilterGuard,
                                HotspotCacheBreaker hotspotCacheBreaker,
                                CacheWarmup cacheWarmup,
                                LocalRemoteCacheChain localRemoteCacheChain) {
        this.bloomFilterGuard = bloomFilterGuard;
        this.hotspotCacheBreaker = hotspotCacheBreaker;
        this.cacheWarmup = cacheWarmup;
        this.localRemoteCacheChain = localRemoteCacheChain;
    }

    // ============================================================
    // 模块一：缓存穿透防御 (Bloom Filter + Cache Null)
    // ============================================================

    @GetMapping("/stage3/user")
    public String getUser(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        return bloomFilterGuard.getUserInfo(resolvedUserId);
    }

    @GetMapping("/stage3/user/add")
    public String addUser(String userId, String nickname) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10003" : userId;
        String resolvedNickname = (nickname == null || nickname.isBlank()) ? "Bob" : nickname;

        bloomFilterGuard.addExistingUser(
                resolvedUserId,
                "{\"userId\":\"" + resolvedUserId + "\",\"nickname\":\"" + resolvedNickname + "\"}"
        );

        return "已添加用户：" + resolvedUserId;
    }


    // ============================================================
    // 模块二：缓存击穿防御 (Redisson Lock + Double Check)
    // ============================================================

    @GetMapping("/stage3/hot-user")
    public String getHotUser(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        return hotspotCacheBreaker.getHotUserInfo(resolvedUserId);
    }

    @GetMapping("/stage3/hot-user/delete-cache")
    public String deleteHotUserCache(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        hotspotCacheBreaker.deleteCache(resolvedUserId);
        return "已删除热点用户缓存：" + resolvedUserId;
    }


    // ============================================================
    // 模块三：缓存雪崩防御 (Warmup + TTL Jitter)
    // ============================================================

    @GetMapping("/stage3/warmup")
    public Map<String, Long> warmup() {
        return cacheWarmup.warmupHotUsers();
    }

    @GetMapping("/stage3/warmup/ttl")
    public Map<String, Long> warmupTtl() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("10001", cacheWarmup.getTtlSeconds("10001"));
        result.put("10002", cacheWarmup.getTtlSeconds("10002"));
        result.put("10003", cacheWarmup.getTtlSeconds("10003"));
        return result;
    }


    // ============================================================
    // 模块四：多级缓存架构 (L1 Caffeine + L2 Redis)
    // ============================================================

    /**
     * 查询用户信息（走多级缓存链路）
     *
     * <p>测试三连跳：</p>
     * 1. 第一次访问：预计返回“数据库命中，并写入 Redis + Caffeine”
     * 2. 第二次访问：预计返回“Caffeine 本地缓存命中”（速度极快，不走网络）
     * 3. 调用 /clear-local 后再次访问：预计返回“Redis 远程缓存命中”（Caffeine 没了，但 Redis 还有）
     */
    @GetMapping("/stage3/chain/user")
    public String getChainUser(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        return localRemoteCacheChain.getUserInfo(resolvedUserId);
    }

    /**
     * 更新用户信息（测试多级缓存的淘汰策略）
     * * <p>测试逻辑：更新 DB -> 删除 Redis (L2) -> 失效 Caffeine (L1)</p>
     */
    @GetMapping("/stage3/chain/user/update")
    public String updateChainUser(String userId, String nickname) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        String resolvedNickname = (nickname == null || nickname.isBlank()) ? "Kevin Updated" : nickname;
        return localRemoteCacheChain.updateUserInfo(resolvedUserId, resolvedNickname);
    }

    /**
     * 辅助测试：仅清理本地缓存 (L1)
     */
    @GetMapping("/stage3/chain/user/clear-local")
    public String clearChainLocal(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        localRemoteCacheChain.clearLocalCache(resolvedUserId);
        return "已清理 Caffeine 本地缓存：" + resolvedUserId;
    }

    /**
     * 辅助测试：仅清理远程缓存 (L2)
     */
    @GetMapping("/stage3/chain/user/clear-redis")
    public String clearChainRedis(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10001" : userId;
        localRemoteCacheChain.clearRedisCache(resolvedUserId);
        return "已清理 Redis 远程缓存：" + resolvedUserId;
    }
}