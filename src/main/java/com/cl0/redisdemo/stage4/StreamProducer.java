package com.cl0.redisdemo.stage4;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线消息流生产者服务类
 * <p>
 * 该类主要用于将用户的离线消息推送到 Redis Stream 中。
 * 为每个用户维护一个独立的 Stream 作为轻量级消息队列，
 * 并通过限制最大长度来防止 Redis 内存无限膨胀。
 */
@Service
public class StreamProducer {

    /**
     * 离线消息 Stream 的 Redis Key 前缀
     */
    private static final String OFFLINE_STREAM_KEY_PREFIX = "offline:stream:";
    
    /**
     * 每个用户的离线消息流最大保留长度。
     * 超过此长度时，最老的消息将被剔除。
     */
    private static final long MAX_STREAM_LENGTH = 1000L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数注入 StringRedisTemplate
     *
     * @param redisTemplate Spring 提供的 Redis 字符串操作模板
     */
    public StreamProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 添加离线消息到用户的专属 Redis Stream 中
     *
     * @param userId    接收消息的用户 ID (用于构建独有的 Stream Key)
     * @param messageId 业务系统中的消息 ID
     * @param content   消息主体内容
     * @return Redis Stream 自动生成的 RecordId (如 "1691234567890-0")，若添加失败则返回 null
     */
    public String addOfflineMessage(String userId, String messageId, String content) {
        // 1. 构建该用户的专属 Stream Key
        String streamKey = buildOfflineStreamKey(userId);

        // 2. 组装消息体结构（使用 LinkedHashMap 方便在调试时保持字段顺序）
        Map<String, String> body = new LinkedHashMap<>();
        body.put("messageId", messageId);
        body.put("targetUserId", userId);
        body.put("content", content);

        // 3. 构建 Redis Stream 的 Record 记录对象
        MapRecord<String, String, String> record = StreamRecords
                .string(body)
                .withStreamKey(streamKey);

        // 4. 将记录追加到 Redis Stream 中
        RecordId recordId = redisTemplate.opsForStream().add(record);

        // 5. 裁剪 Stream 长度 (Trim)
        // 关键步骤：限制队列长度，丢弃旧数据，保障 Redis 内存健康
        redisTemplate.opsForStream().trim(streamKey, MAX_STREAM_LENGTH);

        // 6. 提取并返回底层的 ID 字符串
        return recordId == null ? null : recordId.getValue();
    }

    /**
     * 获取指定用户的离线消息流当前积压的消息数量
     *
     * @param userId 目标用户 ID
     * @return 当前 Stream 的长度
     */
    public Long streamSize(String userId) {
        return redisTemplate.opsForStream().size(buildOfflineStreamKey(userId));
    }

    /**
     * 内部辅助方法：统一构建用户的离线消息 Stream Key
     *
     * @param userId 目标用户 ID
     * @return 完整的 Redis Key，例如 "offline:stream:10086"
     */
    private String buildOfflineStreamKey(String userId) {
        return OFFLINE_STREAM_KEY_PREFIX + userId;
    }
}