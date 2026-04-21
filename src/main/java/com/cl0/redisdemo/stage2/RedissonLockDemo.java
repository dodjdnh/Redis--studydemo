package com.cl0.redisdemo.stage2;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 基于 Redisson 实现的分布式锁演示类。
 * 核心优势：代码极简，自带“看门狗”续期机制，自动处理锁的安全释放（替代了手写 Lua 脚本）。
 */
@Service
public class RedissonLockDemo {

    // Redisson 专用的锁 Key，和之前手写的锁区分开
    private static final String LOCK_KEY = "demo:lock:redisson-delivery-retry";

    // 注入我们在 RedissonConfig 中配置好的客户端实例
    private final RedissonClient redissonClient;

    public RedissonLockDemo(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 演示默认的 lock() 方法：阻塞式加锁 + 触发看门狗机制
     */
    public String runWithWatchdog() {
        // 1. 获取锁对象 (注意：这一步只是拿到了一个对象引用，并没有去 Redis 里抢锁)
        RLock lock = redissonClient.getLock(LOCK_KEY);

        // 2. 正式去抢锁
        // 调用无参的 lock() 方法意味着：
        // a. 它是阻塞的：抢不到就会一直死等。
        // b. 触发看门狗：默认租期是 30 秒，只要业务没执行完，后台线程每隔 10 秒就会自动去 Redis 里把过期时间重置回 30 秒。
        lock.lock();

        // 3. 拿到锁后，执行业务逻辑
        try {
            return doRetryTask();
        } finally {
            // 4. 安全解锁（超级重点！）
            // lock.isHeldByCurrentThread()：这行代码完美替代了我们之前手写的那个 Lua 脚本！
            // 它的作用是判断：“当前 Redis 里的这把锁，是不是当前线程我自己加的？”
            // 只有是自己的锁，才允许执行 unlock()，防止误删别人的锁。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 模拟耗时的业务逻辑
     */
    private String doRetryTask() {
        try {
            // 模拟休眠 10 秒。
            // 在这 10 秒内，即使有其他线程或者别的服务器实例来访问 runWithWatchdog()，
            // 它们全都会在 lock.lock() 那一行被阻塞住（卡主），排队等待。
            Thread.sleep(10_000L);
        } catch (InterruptedException e) {
            // 恢复线程的中断标志位，这在并发编程中是个好习惯
            Thread.currentThread().interrupt();
            return "任务被中断";
        }

        return "Redisson 抢到锁并执行完成：这次使用的是 RLock 看门狗模式";
    }
}