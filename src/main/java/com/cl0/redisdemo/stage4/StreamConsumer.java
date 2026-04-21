package com.cl0.redisdemo.stage4;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线消息流消费者服务类
 * <p>
 * 该类负责从 Redis Stream 中读取用户的离线消息，并向 Redis 发送处理确认（ACK）。
 * 使用"消费者组 (Consumer Group)"模式，可以保证多台服务器同时读取时，同一条消息只会被处理一次。
 */
@Service
public class StreamConsumer {

    /**
     * 离线消息 Stream 的 Redis Key 前缀 (与生产端保持一致)
     */
    private static final String OFFLINE_STREAM_KEY_PREFIX = "offline:stream:";

    /**
     * 消费者组名称。
     * Redis 会记录这个组内所有消费者消费到了哪个位置。
     */
    private static final String GROUP_NAME = "rtc-delivery-group";

    /**
     * 当前消费者的名字。
     * 在实际生产中，为了区分不同的服务器节点，通常会用 "机器IP + 线程号" 或 UUID。
     * 比如节点A叫 consumer-1，节点B叫 consumer-2，它们同属于一个 Group，就会一起分担消费压力。
     */
    private static final String CONSUMER_NAME = "rtc-consumer-1";

    private final StringRedisTemplate redisTemplate;

    public StreamConsumer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 拉取并处理指定用户的离线消息
     *
     * @param userId 目标用户 ID
     * @param count  本次最多想拉取几条消息
     * @return 返回处理结果列表
     */
    public List<String> readAndAckOfflineMessages(String userId, int count) {
        String streamKey = buildOfflineStreamKey(userId);

        // 1. 安全保障：每次读取前，确保对应的消费者组已经建立。
        // 如果不建组直接读取，Redis 会报错。
        ensureGroup(streamKey);

        // 2. 从消费者组中读取消息 (底层对应 Redis 命令：XREADGROUP)
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                // 告诉 Redis：我是 rtc-delivery-group 组里的 rtc-consumer-1
                Consumer.from(GROUP_NAME, CONSUMER_NAME),

                // 配置读取参数
                StreamReadOptions.empty()
                        .count(count) // 最多读取 count 条
                        .block(Duration.ofSeconds(1)), // 如果没有新消息，在这里阻塞等待 1 秒钟再返回，避免疯狂循环消耗 CPU

                // ReadOffset.lastConsumed() 底层对应 Redis 中的 ">" 符号。
                // 它的核心含义是：只读取那些【从来没有分配给本组内任何消费者】的全新消息！
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

        List<String> result = new ArrayList<>();

        // 如果过了 1 秒还是没有消息，直接返回空结果
        if (records == null || records.isEmpty()) {
            return result;
        }

        // 3. 遍历拿到的每一条消息进行业务处理
        for (MapRecord<String, Object, Object> record : records) {
            // 这里仅仅是拼接字符串，实际业务中你应该在这里把消息通过 WebSocket 发送给客户端
            result.add("读取到消息 recordId=" + record.getId().getValue() + ", body=" + record.getValue());

            // 4. 发送 ACK 确认 (底层对应 Redis 命令：XACK)
            // 【极其关键的一步】：消息一旦被读取，就会进入该消费者的 Pending 列表（待确认列表）。
            // 只有发送了 ACK，Redis 才会认为你处理完了，并把消息从 Pending 列表中彻底删掉。
            // 如果不发送 ACK，下次程序重启或者检查未完成队列时，这条消息还会再次出现。
            redisTemplate.opsForStream().acknowledge(streamKey, GROUP_NAME, record.getId());
        }

        return result;
    }

    /**
     * 查询某个用户目前有多少条【正在处理中（未 ACK）】的消息
     * 这是一个很好的监控方法。如果这个数字一直飙升，说明程序读取了消息但一直没确认，可能卡死了。
     *
     * @param userId 目标用户 ID
     * @return pending 状态的消息数量
     */
    public Long pendingCount(String userId) {
        String streamKey = buildOfflineStreamKey(userId);
        ensureGroup(streamKey);

        // 获取该 Stream 下所有消费者组的信息 (底层对应 Redis 命令：XINFO GROUPS)
        StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);

        // 遍历所有组，找到我们自己的那个组，并提取它的 pendingCount
        return groups.stream()
                .filter(group -> GROUP_NAME.equals(group.groupName()))
                .map(StreamInfo.XInfoGroup::pendingCount)
                .findFirst()
                .orElse(0L);
    }

    /**
     * 内部辅助方法：确保消费者组存在。
     * * @param streamKey 目标 Stream 的 Key
     */
    private void ensureGroup(String streamKey) {
        try {
            // 尝试创建组 (底层对应 Redis 命令：XGROUP CREATE)
            // ReadOffset.from("0-0") 代表：这个组一建出来，就从 Stream 的最开头（第0条）开始认领历史消息。
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), GROUP_NAME);
        } catch (DataAccessException ex) {
            // Redis 没有 "CREATE IF NOT EXISTS" 这种语法。
            // 如果组已经存在了，Redis 就会抛出一个包含 "BUSYGROUP Consumer Group name already exists" 的异常。
            // 这是一个正常的现象，我们只需要把这个特定的异常忽略掉即可。
            String message = ex.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                // 如果是网络断开等其他严重异常，千万不能吞掉，要继续往上抛
                throw ex;
            }
        }
    }

    /**
     * 内部辅助方法：拼接统一的 Redis Key
     */
    private String buildOfflineStreamKey(String userId) {
        return OFFLINE_STREAM_KEY_PREFIX + userId;
    }
}