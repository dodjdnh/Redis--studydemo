package com.cl0.redisdemo.stage2;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 2 综合演示控制器：分布式并发控制与消息幂等性。
 * 该类作为入口，汇聚了从简单的手动 SETNX 锁到高级 Redisson 锁，
 * 以及实际生产中极其重要的“消息去重”方案。
 */
@RestController
public class Stage2DemoController {

    private final RedisSetNxLockDemo redisSetNxLockDemo;
    private final RedissonLockDemo redissonLockDemo;
    private final TryLockWithTimeout tryLockWithTimeout;
    private final MessageDeduplication messageDeduplication;

    /**
     * 构造器注入：Spring 官方推荐，确保所有 Service 在 Controller 启动时即准备就绪。
     */
    public Stage2DemoController(RedisSetNxLockDemo redisSetNxLockDemo,
                                RedissonLockDemo redissonLockDemo,
                                TryLockWithTimeout tryLockWithTimeout,
                                MessageDeduplication messageDeduplication) {
        this.redisSetNxLockDemo = redisSetNxLockDemo;
        this.redissonLockDemo = redissonLockDemo;
        this.tryLockWithTimeout = tryLockWithTimeout;
        this.messageDeduplication = messageDeduplication;
    }

    // ==========================================
    // 第一部分：分布式锁（粗粒度/任务级并发控制）
    // ==========================================

    /**
     * 接口 1：原生 SETNX 实现的分布式锁
     * 效果：非阻塞。一旦有人占用锁，其他人立刻返回失败。
     */
    @GetMapping("/stage2/setnx-lock")
    public String runSetNxLockDemo() {
        return redisSetNxLockDemo.tryRunRetryTask();
    }

    /**
     * 接口 2：Redisson 标准锁 (lock)
     * 效果：无限阻塞。所有人排队等待，配合“看门狗”自动续期，保证任务不中断。
     */
    @GetMapping("/stage2/redisson-lock")
    public String runRedissonLockDemo() {
        return redissonLockDemo.runWithWatchdog();
    }

    /**
     * 接口 3：Redisson 尝试锁 (tryLock)
     * 效果：有限阻塞。在指定的 2 秒内尝试排队，超过时间则放弃，防止线程池堆积。
     */
    @GetMapping("/stage2/try-lock")
    public String runTryLockDemo() {
        return tryLockWithTimeout.runRetryTaskWithTryLock();
    }

    // ==========================================
    // 第二部分：消息防重（细粒度/业务级幂等性）
    // ==========================================

    /**
     * 接口 4：消息去重演示
     * 路径示例：/stage2/dedup?messageId=msg_001
     * * 演示流程：
     * 1. 第一次访问：返回“首次处理...”，并在 Redis 记录该 ID。
     * 2. 第二次访问（相同 ID）：立即返回“重复消息...”，业务逻辑不再执行。
     */
    @GetMapping("/stage2/dedup")
    public String runDedupDemo(String messageId) {
        // 如果 URL 没传 messageId，给一个默认值 "msg-100"
        String resolvedMessageId = messageId == null || messageId.isBlank() ? "msg-100" : messageId;
        return messageDeduplication.handleMessage(resolvedMessageId, "hello");
    }

    /**
     * 接口 5：清理去重标记
     * 作用：方便测试。清理后，之前已经“处理过”的消息 ID 可以再次被“首次处理”。
     */
    @GetMapping("/stage2/dedup/clear")
    public String clearDedupDemo(String messageId) {
        String resolvedMessageId = messageId == null || messageId.isBlank() ? "msg-100" : messageId;
        messageDeduplication.clearDedupMark(resolvedMessageId);
        return "已清理幂等标记：" + resolvedMessageId;
    }
}