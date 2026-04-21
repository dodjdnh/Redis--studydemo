package com.cl0.redisdemo.stage2;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SETNX 实现的分布式锁演示类。
 * 核心原理：利用 Redis 单线程的特性，通过 SET key value NX PX 命令保证跨 JVM 进程的互斥性。
 */
@Service
public class RedisSetNxLockDemo {

    // 锁的键名（Key），所有实例都要去抢这个指定的 Key
    private static final String LOCK_KEY = "demo:lock:delivery-retry";

    // 锁的过期时间（租约时间），设为 60 秒。防止某个拿到锁的实例宕机导致死锁（对应 PX 参数）
    private static final long LOCK_LEASE_MILLIS = 60_000L;

    // 预编译解锁用的 Lua 脚本，避免每次解锁都重新创建对象
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = buildReleaseLockScript();

    private final StringRedisTemplate redisTemplate;

    public RedisSetNxLockDemo(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 业务入口：尝试执行重试任务
     * * @return 执行结果描述
     */
    public String tryRunRetryTask() {
        // 1. 尝试获取锁，并拿到这把锁的唯一标识（Token）
        String lockToken = acquireLock();

        // 2. 如果返回 null，说明没抢到锁，直接打回或返回提示
        if (lockToken == null) {
            return "没有抢到锁：说明已经有别的实例在执行重试任务";
        }

        // 3. 抢到锁了，执行核心业务逻辑
        try {
            return doRetryTask();
        } finally {
            // 4. 无论业务执行成功、失败还是抛出异常，都必须在 finally 块中释放锁
            releaseLock(lockToken);
        }
    }

    /**
     * 加锁逻辑
     * * @return 成功获取锁则返回全局唯一的 token；获取失败返回 null
     */
    private String acquireLock() {
        // 生成全局唯一的标识作为 Value。
        // 作用：解铃还须系铃人，防止后续解锁时误删了其他线程/实例刚抢到的锁。
        String lockToken = UUID.randomUUID().toString();

        // 核心加锁命令，对应 Redis 的：SET demo:lock:delivery-retry <uuid> NX PX 60000
        // Spring Data Redis 封装为了 setIfAbsent 方法
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                LOCK_KEY, // Key
                lockToken, // Value (UUID)
                LOCK_LEASE_MILLIS, // 过期时间数值
                TimeUnit.MILLISECONDS // 过期时间单位
        );

        // 如果 Redis 返回 true，说明抢锁成功，把 token 传递给外层
        return Boolean.TRUE.equals(locked) ? lockToken : null;
    }

    /**
     * 模拟实际的业务执行逻辑
     */
    private String doRetryTask() {
        try {
            // 模拟执行一个耗时 10 秒的任务（例如处理大数据、调用外部缓慢的 API 等）
            Thread.sleep(10_000L);
        } catch (InterruptedException e) {
            // 良好的习惯：捕获中断异常并重新设置中断状态
            Thread.currentThread().interrupt();
        }
        // 这里放入真正需要防并发执行的代码，例如查询数据库中未发送成功的消息并重新发送
        return "抢到锁：开始执行消息重试任务";
    }

    /**
     * 释放锁逻辑
     * * @param lockToken 当前线程持有的锁标识（UUID）
     */
    private void releaseLock(String lockToken) {
        // 如果 token 为空，说明一开始就没拿到锁，不需要释放
        if (lockToken == null) {
            return;
        }

        // 执行 Lua 脚本进行安全解锁
        // 参数1: 预定义的 Lua 脚本
        // 参数2: 传入 KEYS[1] 的值，即锁的键名
        // 参数3: 传入 ARGV[1] 的值，即当前线程持有的唯一标识
        redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY),
                lockToken);
    }

    /**
     * 构建并返回安全的解锁 Lua 脚本
     * * @return DefaultRedisScript 对象
     */
    private static DefaultRedisScript<Long> buildReleaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // 脚本执行返回值为 Long 类型（1代表删除成功，0代表删除失败/不是自己的锁）
        script.setResultType(Long.class);
        // Lua 脚本内容：
        // 先 GET 判断锁的值是否和自己传入的 token 一致；
        // 如果一致（是自己的锁），才执行 DEL 命令删除；
        // 如果不一致（锁已经过期，被别人抢走了），则直接返回 0。
        // 这保证了“判断”和“删除”这两个动作在 Redis 中是一个原子操作。
        script.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else return 0 end");
        return script;
    }
}