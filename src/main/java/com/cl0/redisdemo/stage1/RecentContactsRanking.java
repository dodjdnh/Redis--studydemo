package com.cl0.redisdemo.stage1;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * 最近联系人排行与消息序号生成服务
 * 演示了 ZSet (有序集合) 在排序场景的应用，以及 String 类型的原子自增。
 */
@Service
public class RecentContactsRanking {

    // 最近联系人的 ZSet Key 前缀
    private static final String RECENT_CONTACTS_KEY_PREFIX = "recent:contacts:";
    // 房间消息序号的 String Key 前缀
    private static final String MESSAGE_SEQ_KEY_PREFIX = "msg:seq:";
    
    // 控制每个用户最多保存的最近联系人数量 (防止长期积累导致 ZSet 过大)
    private static final int MAX_RECENT_CONTACTS = 200;

    private final StringRedisTemplate redisTemplate;

    public RecentContactsRanking(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 更新最近联系人 (核心方法)
     * 业务场景：当用户 A 和用户 B 发送/接收了一条消息，调用此方法把 B 顶到 A 的联系人列表最前面。
     *
     * @param userId    当前用户 ID
     * @param sessionId 会话 ID (可以是单聊的对方 ID，也可以是群组 ID)
     */
    public void touchContact(String userId, String sessionId) {
        String key = buildRecentContactsKey(userId);
        
        // 1. 使用当前时间的毫秒时间戳作为分数 (Score)
        double score = Instant.now().toEpochMilli();

        // 2. 对应 Redis 命令: ZADD
        // 如果 sessionId 不存在，则插入；如果已存在，则更新它的 score。
        redisTemplate.opsForZSet().add(key, sessionId, score);

        // 3. 对应 Redis 命令: ZREMRANGEBYRANK (按排名删除区间内的元素)
        // 【核心技巧】：ZSet 默认按分数从低到高排序。0 是最低分(最老)，-1 是最高分(最新)。
        // removeRange(0, -201) 的意思是：从最老的元素开始删，一直删到倒数第 201 个元素。
        // 这样一来，刚好就只保留了分数最高的前 200 个元素。完美实现了容量控制！
        redisTemplate.opsForZSet().removeRange(key, 0, -MAX_RECENT_CONTACTS - 1);
    }

    /**
     * 获取最近联系人列表 (分页获取)
     * 业务场景：前端打开聊天列表页面，按时间倒序展示联系人。
     *
     * @param userId 当前用户 ID
     * @param start  起始索引 (例如 0)
     * @param end    结束索引 (例如 19)
     * @return 返回按时间从新到老排序的联系人 ID 集合
     */
    public Set<String> listRecentContacts(String userId, int start, int end) {
        // 对应 Redis 命令: ZREVRANGE (Reverse Range)
        // 因为 ZSet 默认是从小到大，而我们需要最新（分数最高）的排在前面，所以必须用 reverse 逆序获取。
        return redisTemplate.opsForZSet().reverseRange(buildRecentContactsKey(userId), start, end);
    }

    /**
     * 统计联系人总数
     * 对应 Redis 命令: ZCARD
     */
    public Long countRecentContacts(String userId) {
        return redisTemplate.opsForZSet().zCard(buildRecentContactsKey(userId));
    }

    /**
     * 获取下一个严格递增的消息序号 (Seq ID)
     * 业务场景：为房间内的新消息生成唯一的、连续的序号，保证消息绝对有序、防丢失。
     *
     * @param roomId 房间 ID
     * @return 递增后的序号
     */
    public Long nextMessageSeq(String roomId) {
        // 对应 Redis 命令: INCR
        // 这是一个原子操作，在高并发下也不会产生重复的数字。
        return redisTemplate.opsForValue().increment(buildMessageSeqKey(roomId));
    }

    private String buildRecentContactsKey(String userId) {
        return RECENT_CONTACTS_KEY_PREFIX + userId;
    }

    private String buildMessageSeqKey(String roomId) {
        return MESSAGE_SEQ_KEY_PREFIX + roomId;
    }
}