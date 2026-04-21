package com.cl0.redisdemo.stage1;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线消息队列服务
 * 使用 Redis List 类型实现 FIFO (先进先出) 队列。
 * 专门用于存储和读取接收方处于离线状态时的聊天消息。
 */
@Service
public class OfflineMessageQueue {

    // 离线消息队列的 Key 前缀，按照业务和用户划分
    private static final String OFFLINE_MESSAGE_KEY_PREFIX = "offline:msg:";
    
    // 队列最大长度限制（防雪崩机制）
    // 防止某个用户长期不登录（比如卸载了 App），导致其离线消息无限堆积撑爆 Redis 内存
    private static final int MAX_OFFLINE_MESSAGES = 1000;
    
    // 离线消息的最长保留时间
    // 超过这个时间用户仍未登录拉取，消息将自动销毁
    private static final Duration OFFLINE_MESSAGE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public OfflineMessageQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存离线消息 (存入队列)
     * 对应流程：发送者发来消息 -> 接收方离线 -> 将消息序列化为 JSON 存入此队列
     *
     * @param userId      接收方用户的 ID
     * @param messageJson 序列化后的消息体 (包含发送人、时间、内容等)
     */
    public void pushOfflineMessage(String userId, String messageJson) {
        String key = buildOfflineMessageKey(userId);

        // 1. LPUSH (Left Push): 从列表的最左侧（头部）插入一条新消息
        redisTemplate.opsForList().leftPush(key, messageJson);

        // 2. LTRIM (List Trim): 裁剪列表，只保留指定区间内的元素
        // 这里保留从索引 0 到 999 的元素。
        // 因为我们是 LPUSH，最新的消息在左侧（索引 0），所以 LTRIM 会把最右侧（最老）超出限制的消息丢弃。
        redisTemplate.opsForList().trim(key, 0, MAX_OFFLINE_MESSAGES - 1);

        // 3. 每次有新消息进入，重置该用户离线队列的过期时间
        redisTemplate.expire(key, OFFLINE_MESSAGE_TTL);
    }

    /**
     * 拉取并移除离线消息 (消费队列)
     * 对应流程：用户登录 / 重连 WebSocket -> 查询并拉取属于自己的离线消息
     *
     * @param userId   接收方用户的 ID
     * @param maxCount 本次最多拉取多少条 (分页/分批拉取，防止单次数据量过大导致网络阻塞)
     * @return 离线消息 JSON 列表
     */
    public List<String> popOfflineMessages(String userId, int maxCount) {
        String key = buildOfflineMessageKey(userId);
        List<String> messages = new ArrayList<>();

        // 循环取出消息
        for (int i = 0; i < maxCount; i++) {
            // 1. RPOP (Right Pop): 从列表的最右侧（尾部）弹出一个元素并将其从 Redis 中删除
            // 因为 push 时是 LPUSH（新消息在左），所以最右侧的永远是“最早”进入队列的消息。
            // 这样就实现了 FIFO (先进先出)，保证了聊天的时序性。
            String message = redisTemplate.opsForList().rightPop(key);
            
            // 如果取不到值，说明队列已经空了，直接中断循环
            if (message == null) {
                break;
            }
            messages.add(message);
        }

        return messages;
    }

    /**
     * 获取当前积压的离线消息数量
     * 用于在客户端显示“您有 X 条未读消息”，或者在拉取前评估数据量
     *
     * @param userId 用户的 ID
     * @return 队列长度
     */
    public Long countOfflineMessages(String userId) {
        // 对应 Redis 命令: LLEN (List Length)
        // 这是一个 O(1) 复杂度的操作，因为 Redis 内部维护了 List 的长度属性，非常高效
        return redisTemplate.opsForList().size(buildOfflineMessageKey(userId));
    }

    /**
     * 构造存储离线消息的 Redis Key
     */
    private String buildOfflineMessageKey(String userId) {
        return OFFLINE_MESSAGE_KEY_PREFIX + userId;
    }
}