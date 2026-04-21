package com.cl0.redisdemo.stage3;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 布隆过滤器拦截器（防止缓存穿透实战示例）
 *
 * <p>
 * 本类展示了解决“缓存穿透”问题的经典双重防御机制：
 * 1. 第一道防线：布隆过滤器拦截。拦截绝大部分明显不存在的非法请求，保护数据库。
 * 2. 第二道防线：空值缓存（Cache Null Value）。应对布隆过滤器的“误判”或数据的短期物理删除，防止个别漏网之鱼反复冲击数据库。
 * </p>
 */
@Service
public class BloomFilterGuard {

    // ======================== 常量配置 ========================
    
    /** Redis 用户信息缓存 Key 前缀 */
    private static final String USER_INFO_KEY_PREFIX = "user:info:";
    
    /** 空值缓存标记，用于防止缓存穿透 */
    private static final String NULL_VALUE = "__NULL__";
    
    /** 正常业务数据的缓存过期时间（30分钟） */
    private static final Duration USER_INFO_TTL = Duration.ofMinutes(30);
    
    /** 空值标记的缓存过期时间（2分钟，通常设置较短，防止长时间占用内存或掩盖真实数据更新） */
    private static final Duration NULL_VALUE_TTL = Duration.ofMinutes(2);

    
    // ======================== 核心依赖 ========================
    
    private final StringRedisTemplate redisTemplate;

    /** * 模拟本地布隆过滤器 
     * (注：生产环境中，布隆过滤器通常是基于 RedisBloom 的位数组（BitMap）或 Guava 的 BloomFilter，
     * 这里为了演示业务逻辑，暂用 HashSet 代替。HashSet 会占用大量内存，而真正的布隆过滤器非常节省内存。) 
     */
    private final Set<String> existingUserIds = new HashSet<>();
    
    /** 模拟底层关系型数据库（如 MySQL） */
    private final Map<String, String> fakeDatabase = new HashMap<>();


    /**
     * 构造函数：注入 Redis 模板并初始化模拟数据
     */
    public BloomFilterGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        // 1. 初始化数据库中的真实数据
        fakeDatabase.put("10001", "{\"userId\":\"10001\",\"nickname\":\"Kevin\"}");
        fakeDatabase.put("10002", "{\"userId\":\"10002\",\"nickname\":\"Alice\"}");

        // 2. 缓存预热：将真实存在的 userId 加载到“布隆过滤器”中
        existingUserIds.add("10001");
        existingUserIds.add("10002");
    }

    /**
     * 获取用户信息核心主流程（完整防穿透链路）
     *
     * @param userId 用户ID
     * @return 查询结果及来源描述（实际业务中通常返回 UserDTO 或 JSON）
     */
    public String getUserInfo(String userId) {
        // 第一步：【第一道防线】布隆过滤器拦截
        // 如果布隆过滤器认为该 ID 绝对不存在，直接驳回，绝不访问 Redis 和 DB
        if (!mightExist(userId)) {
            return "布隆过滤器拦截：userId=" + userId + " 一定不存在";
        }

        // 第二步：查询 Redis 缓存
        String key = buildUserInfoKey(userId);
        String cached = redisTemplate.opsForValue().get(key);

        // 第三步：【第二道防线】判断是否命中“空值缓存”
        if (NULL_VALUE.equals(cached)) {
            return "空值缓存命中：userId=" + userId + " 不存在";
        }

        // 第四步：正常命中 Redis 缓存，直接返回业务数据
        if (cached != null) {
            return "Redis 缓存命中：" + cached;
        }

        // 第五步：Redis 未命中，发生缓存穿透（Cache Miss），准备查询数据库（DB）
        String dbValue = fakeDatabase.get(userId);

        // 第六步：数据库中也不存在该数据（原因可能是布隆过滤器的哈希碰撞误判，或者数据刚被删除）
        if (dbValue == null) {
            // 写入空值标记到 Redis，并设置较短的 TTL，防止未来相同的无效请求再次打到 DB
            redisTemplate.opsForValue().set(key, NULL_VALUE, NULL_VALUE_TTL);
            return "数据库未查到，写入空值缓存：userId=" + userId;
        }

        // 第七步：数据库命中，将真实数据回写（Cache Update）到 Redis，并返回
        redisTemplate.opsForValue().set(key, dbValue, USER_INFO_TTL);
        return "数据库查到并写入 Redis：" + dbValue;
    }

    /**
     * 新增用户操作（演示如何维护布隆过滤器和缓存的一致性）
     *
     * @param userId   新用户ID
     * @param userJson 用户信息 JSON 字符串
     */
    public void addExistingUser(String userId, String userJson) {
        // 1. 写入底层数据库
        fakeDatabase.put(userId, userJson);
        
        // 2. 将新 ID 登记到布隆过滤器
        // (注：标准的布隆过滤器一般不支持删除操作，只支持增加。因此如果是删除数据，通常只能靠空值缓存来兜底)
        existingUserIds.add(userId);
        
        // 3. 删除可能存在的空值缓存或旧缓存，保证下一次查询时能读到最新 DB 数据（Cache Eviction）
        redisTemplate.delete(buildUserInfoKey(userId));
    }

    /**
     * 模拟布隆过滤器的存在性判断
     * * @param userId 用户ID
     * @return true 代表“可能存在”（有误判率），false 代表“绝对不存在”（百分百准确）
     */
    private boolean mightExist(String userId) {
        return existingUserIds.contains(userId);
    }

    /**
     * 统一构建 Redis 缓存 Key
     */
    private String buildUserInfoKey(String userId) {
        return USER_INFO_KEY_PREFIX + userId;
    }
}