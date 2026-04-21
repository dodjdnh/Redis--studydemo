package com.cl0.redisdemo.stage5;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 消息序号生成器 (Message Sequence Generator)
 * * 核心架构作用：
 * 在 IM（即时通讯）或多人聊天室场景中，不能依赖客户端或服务器的系统时间来排序消息（因为会有时间同步误差/时钟回拨问题）。
 * 必须使用一个绝对递增的序号（Seq）来确保：
 * 1. 严格有序：Seq 大的必定是后发出的消息。
 * 2. 防丢失（空洞检测）：如果客户端收到了 Seq=5 和 Seq=7 的消息，它立刻就能知道丢失了 Seq=6 的消息，从而主动向服务端拉取补齐。
 * * 底层依赖：利用 Redis 的 INCR (原子自增) 指令保证极高并发下的序号唯一性和递增性。
 */
@Service
public class MessageSeqGenerator {

    // Redis Key 的前缀，规范命名空间。例如某个房间的 key 会是 "msg:seq:room-101"
    private static final String MESSAGE_SEQ_KEY_PREFIX = "msg:seq:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数注入
     */
    public MessageSeqGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取指定房间的下一个消息序号（最核心方法）
     * * 底层 Redis 命令：INCR key
     * 架构亮点：Redis 是单线程处理命令的，INCR 是【原子操作】。
     * 哪怕 10000 个人在同一毫秒向同一个房间发消息，Redis 也会排队给他们依次返回 1, 2, 3...，绝对不会出现重复的序号。
     *
     * @param roomId 聊天室或群组的唯一 ID
     * @return 严格递增的全局唯一序号
     */
    public Long nextSeq(String roomId) {
        // opsForValue().increment() 如果发现 Key 不存在，会先自动创建并设为 0，然后再 +1 返回 1。
        return redisTemplate.opsForValue().increment(buildSeqKey(roomId));
    }

    /**
     * 查询指定房间当前的最新序号（仅查询，不增加）
     * * 底层 Redis 命令：GET key
     * 业务场景：常用于客户端刚进入房间时，拉取当前房间已经聊到第几条消息了，以此为基准开始同步。
     *
     * @param roomId 聊天室或群组的唯一 ID
     * @return 当前最新的序号，如果房间还没人发过消息，返回 0
     */
    public Long currentSeq(String roomId) {
        String value = redisTemplate.opsForValue().get(buildSeqKey(roomId));
        // 防御性编程：如果该房间从来没有发过消息（Key 不存在），返回 0
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    /**
     * 重置/清空指定房间的序号
     * * 底层 Redis 命令：DEL key
     * 业务场景：聊天室解散，或者定时清理历史数据时调用。
     *
     * @param roomId 聊天室或群组的唯一 ID
     */
    public void resetSeq(String roomId) {
        redisTemplate.delete(buildSeqKey(roomId));
    }

    /**
     * 内部辅助方法：统一拼装 Redis 的 Key
     *
     * @param roomId 房间 ID
     * @return 完整的 Redis Key
     */
    private String buildSeqKey(String roomId) {
        return MESSAGE_SEQ_KEY_PREFIX + roomId;
    }
}