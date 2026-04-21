package com.cl0.redisdemo.stage1;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间/群组成员管理器
 * 使用 Redis Set 类型管理，保证成员唯一性，并提供高效的成员查询和安全的分批遍历能力。
 */
@Service
public class RoomMemberManager {

    // 房间成员 Key 的前缀
    private static final String ROOM_MEMBERS_KEY_PREFIX = "room:members:";

    private final StringRedisTemplate redisTemplate;

    public RoomMemberManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 添加成员到房间 (用户进群/进入直播间)
     * 对应 Redis 命令: SADD
     * 业务优势: Set 会自动去重。如果用户已经在房间里，再次添加也不会报错，只是返回 0。
     *
     * @param roomId 房间 ID
     * @param userId 用户 ID
     * @return 成功添加到集合的新元素数量 (1 表示新加入，0 表示已存在)
     */
    public Long addMember(String roomId, String userId) {
        return redisTemplate.opsForSet().add(buildRoomMembersKey(roomId), userId);
    }

    /**
     * 从房间移除成员 (用户退群/被踢出)
     * 对应 Redis 命令: SREM
     *
     * @param roomId 房间 ID
     * @param userId 用户 ID
     * @return 成功移除的元素数量
     */
    public Long removeMember(String roomId, String userId) {
        return redisTemplate.opsForSet().remove(buildRoomMembersKey(roomId), userId);
    }

    /**
     * 判断用户是否在房间内 (鉴权逻辑)
     * 对应 Redis 命令: SISMEMBER
     * 业务场景: 当用户在群里发消息时，Netty 后端先调用此方法，时间复杂度 O(1)，极快判断他是不是群成员。
     *
     * @param roomId 房间 ID
     * @param userId 用户 ID
     * @return true 存在, false 不存在
     */
    public Boolean isMember(String roomId, String userId) {
        return redisTemplate.opsForSet().isMember(buildRoomMembersKey(roomId), userId);
    }

    /**
     * 获取房间内的总人数
     * 对应 Redis 命令: SCARD
     * 业务场景: 在群聊界面顶部显示“群成员(500人)”。
     *
     * @param roomId 房间 ID
     * @return 成员数量
     */
    public Long countMembers(String roomId) {
        return redisTemplate.opsForSet().size(buildRoomMembersKey(roomId));
    }

    /**
     * 安全地分批获取房间成员 (游标扫描)
     * 对应 Redis 命令: SSCAN
     * * @param roomId    房间 ID
     * @param batchSize 每次底层向 Redis 请求扫描的数量 (比如 100)
     * @param maxCount  本次 API 调用期望返回的最大结果数 (防止返回过大数据撑爆 JVM)
     * @return 成员 ID 列表
     */
    public List<String> scanMembers(String roomId, int batchSize, int maxCount) {
        String key = buildRoomMembersKey(roomId);
        List<String> result = new ArrayList<>();

        // 配置扫描选项：告诉 Redis 每次底层迭代扫描多少条数据
        ScanOptions options = ScanOptions.scanOptions()
                .count(batchSize)
                .build();

        // 【极其重要】：Cursor 实现了 Closeable 接口，内部持有了 Redis 的连接！
        // 必须使用 try-with-resources 语法确保游标被关闭，否则会导致 Redis 连接池泄漏，整个服务崩溃。
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(key, options)) {
            // 只要还有下一条数据，且当前收集的数量还没达到期望的最大值，就继续收集
            while (cursor.hasNext() && result.size() < maxCount) {
                result.add(cursor.next());
            }
        }

        return result;
    }

    /**
     * 构造存储房间成员的 Redis Key
     */
    private String buildRoomMembersKey(String roomId) {
        return ROOM_MEMBERS_KEY_PREFIX + roomId;
    }
}