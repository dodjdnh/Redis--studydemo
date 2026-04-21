package com.cl0.redisdemo.stage5;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天室/群组消息广播服务
 * * 核心架构梳理：
 * 1. 成员管理：使用 Redis Set 存储房间内的用户 ID，保证成员不重复。
 * 2. 消息序号：调用 MessageSeqGenerator 保证群聊消息绝对有序。
 * 3. 跨节点路由：调用 OnlineStatusService 确定目标用户在哪台服务器，
 * 然后利用 Redis Pub/Sub 将消息广播出去，由对应的服务器接手推送。
 */
@Service
public class RoomBroadcastService {

    // Redis Key 前缀：用于存储房间内的成员列表 (数据结构：Set)
    private static final String ROOM_MEMBERS_KEY_PREFIX = "room:members:";
    // Redis Pub/Sub 频道名：集群中所有的服务器都会监听这个频道，等待认领属于自己的消息
    private static final String ROOM_BROADCAST_CHANNEL = "im:room:broadcast";

    private final StringRedisTemplate redisTemplate;
    private final OnlineStatusService onlineStatusService;
    private final MessageSeqGenerator messageSeqGenerator;

    /**
     * 构造函数注入，整合三大核心组件
     */
    public RoomBroadcastService(StringRedisTemplate redisTemplate,
                                OnlineStatusService onlineStatusService,
                                MessageSeqGenerator messageSeqGenerator) {
        this.redisTemplate = redisTemplate;
        this.onlineStatusService = onlineStatusService;
        this.messageSeqGenerator = messageSeqGenerator;
    }

    // ==========================================
    // 模块 1：房间成员管理 (基于 Redis Set)
    // ==========================================

    /**
     * 用户加入房间
     * 底层命令：SADD key member
     * 特性：Set 自动去重，即使同一个用户疯狂点击加入，Redis 里也只有一份。
     */
    public Long addRoomMember(String roomId, String userId) {
        return redisTemplate.opsForSet().add(buildRoomMembersKey(roomId), userId);
    }

    /**
     * 用户离开房间
     * 底层命令：SREM key member
     */
    public Long removeRoomMember(String roomId, String userId) {
        return redisTemplate.opsForSet().remove(buildRoomMembersKey(roomId), userId);
    }

    /**
     * 获取房间内的所有成员
     * 底层命令：SMEMBERS key
     */
    public List<String> getRoomMembers(String roomId) {
        var members = redisTemplate.opsForSet().members(buildRoomMembersKey(roomId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(members);
    }

    // ==========================================
    // 模块 2：核心广播逻辑 (分布式消息路由)
    // ==========================================

    /**
     * 在房间内广播消息 (核心业务流)
     *
     * @param roomId       房间 ID
     * @param senderUserId 发送者 ID
     * @param content      消息内容
     * @return 详细的投递报告
     */
    public Map<String, Object> broadcastToRoom(String roomId, String senderUserId, String content) {
        // 1. 获取房间所有成员
        List<String> members = getRoomMembers(roomId);
        
        // 2. 为这条消息申请一个该房间内绝对递增的序号 (防乱序)
        Long seq = messageSeqGenerator.nextSeq(roomId);

        List<Map<String, Object>> deliveries = new ArrayList<>();

        // 3. 遍历成员进行消息投递（写扩散模式：群里有 100 人，就投递 99 次）
        for (String memberUserId : members) {
            // 规则：不要把消息发给发送者自己 (客户端通常自己在本地上屏展示)
            if (memberUserId == null || memberUserId.equals(senderUserId)) {
                continue;
            }

            // 4. 查询目标用户当前是否在线，以及连在集群中的哪台机器上
            String nodeId = onlineStatusService.getOnlineNode(memberUserId);

            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("targetUserId", memberUserId);
            delivery.put("online", nodeId != null);
            delivery.put("targetNodeId", nodeId);

            // 5. 分支处理：在线走 Pub/Sub 实时路由，离线走持久化队列
            if (nodeId != null) {
                // 将路由信息（targetNodeId）和消息体打包成 JSON
                String messageJson = buildBroadcastMessage(roomId, senderUserId, memberUserId, seq, nodeId, content);
                
                // 将消息扔进 Redis 的 Pub/Sub 广播频道。
                // 此时，集群里的所有服务器都会收到这条 JSON。但只有发现 `targetNodeId` 是自己的那台服务器，
                // 才会真正通过 WebSocket 把消息推给 targetUserId，其他服务器直接丢弃。
                Long subscriberCount = redisTemplate.convertAndSend(ROOM_BROADCAST_CHANNEL, messageJson);
                
                delivery.put("pubSubSubscriberCount", subscriberCount);
                delivery.put("message", "已广播到频道，由目标节点 (" + nodeId + ") 自行判断是否处理");
            } else {
                delivery.put("pubSubSubscriberCount", 0L);
                // 架构演进提示：如果用户离线，这里不应该丢弃消息。
                // 真实的生产环境中，这里应该调用 Stage 4 的代码，将消息写入该用户的 Redis Stream 离线队列，并更新未读红点。
                delivery.put("message", "用户当前离线，实际项目里这里应该转离线队列或 Stream");
            }

            deliveries.add(delivery);
        }

        // 6. 组装最终结果返回给调用方
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", roomId);
        result.put("senderUserId", senderUserId);
        result.put("seq", seq);
        result.put("memberCount", members.size());
        result.put("deliveries", deliveries);
        return result;
    }

    // ==========================================
    // 私有辅助方法
    // ==========================================

    /**
     * 手动拼接 JSON 字符串 (仅为演示，生产环境请使用 Jackson/Gson 序列化对象)
     * 注意：这里把 targetNodeId 放进去了，这是分布式路由的关键凭证。
     */
    private String buildBroadcastMessage(String roomId,
                                         String senderUserId,
                                         String targetUserId,
                                         Long seq,
                                         String targetNodeId,
                                         String content) {
        return "{"
                + "\"roomId\":\"" + roomId + "\","
                + "\"senderUserId\":\"" + senderUserId + "\","
                + "\"targetUserId\":\"" + targetUserId + "\","
                + "\"targetNodeId\":\"" + targetNodeId + "\","
                + "\"seq\":" + seq + ","
                + "\"content\":\"" + content + "\""
                + "}";
    }

    private String buildRoomMembersKey(String roomId) {
        return ROOM_MEMBERS_KEY_PREFIX + roomId;
    }
}