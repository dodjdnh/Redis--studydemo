package com.cl0.redisdemo.stage2;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的 tryLock (有限阻塞) 分布式锁演示类。
 * 核心特性：只在门口等指定的时间，过时不候，不会让线程一直挂死。
 */
@Service
public class TryLockWithTimeout {

    // 专用的锁 Key，和其他 Demo 区分开
    private static final String LOCK_KEY = "demo:lock:try-lock-delivery-retry";

    private final RedissonClient redissonClient;

    public TryLockWithTimeout(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 演示 tryLock() 方法：带超时时间的尝试加锁
     */
    public String runRetryTaskWithTryLock() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // 增加一个局部变量，记录当前线程到底有没有真正抢到锁
        boolean locked = false;

        try {
            // 核心逻辑：尝试获取锁，最多等待 2 秒。
            // 注意：这里只传了 waitTime(2秒)，没有传 leaseTime(租期)。
            // 因此，如果抢到了锁，Redisson 的“看门狗”依然会自动生效，帮你无限续期！
            locked = lock.tryLock(2, TimeUnit.SECONDS);

            // 如果等了 2 秒还是没抢到锁，直接放弃，不往下执行业务了
            if (!locked) {
                return "2 秒内没有抢到锁：跳过本轮重试任务 (优雅降级)";
            }

            // 成功抢到锁，执行真正的业务
            return doRetryTask();
            
        } catch (InterruptedException e) {
            // 为什么这里要抓 InterruptedException？
            // 因为 tryLock 会引发线程最多 2 秒的阻塞休眠。在这期间，如果应用被关闭或线程池强行中断，
            // 就会抛出这个异常。我们需要恢复线程的中断标志位，并安全退出。
            Thread.currentThread().interrupt();
            return "等待锁时线程被中断：跳过本轮重试任务";
            
        } finally {
            // 安全解锁（进阶版防护）
            // 为什么要加 locked 判断？
            // 如果你根本就没抢到锁 (locked == false)，直接去执行 unlock 可能会抛出 IllegalMonitorStateException 异常。
            // 所以正确的姿势是：我确实拿到了锁，并且现在这把锁还在我手里，我才去解。
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 模拟耗时的业务逻辑
     */
    private String doRetryTask() {
        try {
            // 模拟执行 10 秒
            Thread.sleep(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "任务执行时被中断";
        }

        return "tryLock 抢到锁并执行完成：抢不到时不会一直阻塞";
    }
}