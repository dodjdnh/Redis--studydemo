package com.cl0.redisdemo.stage5;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 演示控制器：集成在线状态、序列号生成、未读数及房间广播
 */
@RestController
@RequestMapping("/stage5")
@RequiredArgsConstructor
public class Stage5DemoController {

    private final OnlineStatusService onlineStatusService;
    private final MessageSeqGenerator messageSeqGenerator;
    private final UnreadCountServiceV2 unreadCountServiceV2;
    private final RoomBroadcastService roomBroadcastService;
    private final RoomBroadcastSubscriber roomBroadcastSubscriber;

    // --- 在线状态 (Online Status) ---

    @GetMapping("/online")
    public Map<String, Object> markOnline(@RequestParam(required = false) String userId,
                                          @RequestParam(required = false) String nodeId) {
        String resUserId = resolve(userId, "10001");
        String resNodeId = resolve(nodeId, "rtc-node-1");

        onlineStatusService.markOnline(resUserId, resNodeId);
        return buildOnlineStatusMap(resUserId);
    }

    @GetMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestParam(required = false) String userId) {
        String resUserId = resolve(userId, "10001");
        boolean renewed = onlineStatusService.heartbeat(resUserId);

        Map<String, Object> result = buildOnlineStatusMap(resUserId);
        result.put("renewed", renewed);
        return result;
    }

    @GetMapping("/offline")
    public Map<String, Object> offline(@RequestParam(required = false) String userId) {
        String resUserId = resolve(userId, "10001");
        onlineStatusService.markOffline(resUserId);
        return buildOnlineStatusMap(resUserId);
    }

    @GetMapping("/online/status")
    public Map<String, Object> onlineStatus(@RequestParam(required = false) String userId) {
        return buildOnlineStatusMap(resolve(userId, "10001"));
    }

    // --- 消息序列号 (Sequence Generator) ---

    @GetMapping("/seq/next")
    public Map<String, Object> nextSeq(@RequestParam(required = false) String roomId) {
        String resRoomId = resolve(roomId, "room-2001");
        Long nextSeq = messageSeqGenerator.nextSeq(resRoomId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", resRoomId);
        result.put("nextSeq", nextSeq);
        result.put("currentSeq", messageSeqGenerator.currentSeq(resRoomId));
        return result;
    }

    @GetMapping("/seq/current")
    public Map<String, Object> currentSeq(@RequestParam(required = false) String roomId) {
        String resRoomId = resolve(roomId, "room-2001");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", resRoomId);
        result.put("currentSeq", messageSeqGenerator.currentSeq(resRoomId));
        return result;
    }

    @GetMapping("/seq/reset")
    public Map<String, Object> resetSeq(@RequestParam(required = false) String roomId) {
        String resRoomId = resolve(roomId, "room-2001");
        messageSeqGenerator.resetSeq(resRoomId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", resRoomId);
        result.put("currentSeq", messageSeqGenerator.currentSeq(resRoomId));
        return result;
    }

    // --- 未读计数 (Unread Count) ---

    @GetMapping("/unread/increase")
    public Map<String, Object> increaseUnread(String userId, String sessionId) {
        String resUserId = resolve(userId, "10002");
        String resSessionId = resolve(sessionId, "room-2001");

        Long unread = unreadCountServiceV2.increaseUnread(resUserId, resSessionId);
        return buildUnreadResult(resUserId, resSessionId, unread);
    }

    @GetMapping("/unread/get")
    public Map<String, Object> getUnread(String userId, String sessionId) {
        String resUserId = resolve(userId, "10002");
        String resSessionId = resolve(sessionId, "room-2001");

        Long unread = unreadCountServiceV2.getUnread(resUserId, resSessionId);
        return buildUnreadResult(resUserId, resSessionId, unread);
    }

    @GetMapping("/unread/all")
    public Map<String, Object> allUnread(String userId) {
        String resUserId = resolve(userId, "10002");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", resUserId);
        result.put("allUnread", unreadCountServiceV2.getAllUnread(resUserId));
        result.put("totalUnread", unreadCountServiceV2.getTotalUnread(resUserId));
        return result;
    }

    @GetMapping("/unread/clear")
    public Map<String, Object> clearUnread(String userId, String sessionId) {
        String resUserId = resolve(userId, "10002");
        String resSessionId = resolve(sessionId, "room-2001");

        unreadCountServiceV2.clearUnread(resUserId, resSessionId);
        return getUnread(resUserId, resSessionId);
    }

    @GetMapping("/unread/clear-all")
    public Map<String, Object> clearAllUnread(String userId) {
        String resUserId = resolve(userId, "10002");
        unreadCountServiceV2.clearAllUnread(resUserId);
        return allUnread(resUserId);
    }

    // --- 房间广播 (Room Broadcast) ---

    @GetMapping("/room/member/add")
    public Map<String, Object> addRoomMember(String roomId, String userId) {
        String resRoomId = resolve(roomId, "room-2001");
        roomBroadcastService.addRoomMember(resRoomId, resolve(userId, "10001"));
        return buildRoomMembersMap(resRoomId);
    }

    @GetMapping("/room/member/remove")
    public Map<String, Object> removeRoomMember(String roomId, String userId) {
        String resRoomId = resolve(roomId, "room-2001");
        roomBroadcastService.removeRoomMember(resRoomId, resolve(userId, "10001"));
        return buildRoomMembersMap(resRoomId);
    }

    @GetMapping("/room/member/list")
    public Map<String, Object> listRoomMembers(String roomId) {
        return buildRoomMembersMap(resolve(roomId, "room-2001"));
    }

    @GetMapping("/room/broadcast")
    public Map<String, Object> broadcastRoom(String roomId, String senderUserId, String content) {
        return roomBroadcastService.broadcastToRoom(
                resolve(roomId, "room-2001"),
                resolve(senderUserId, "10001"),
                resolve(content, "hello room")
        );
    }

    @GetMapping("/room/broadcast/received")
    public List<String> receivedBroadcastMessages() {
        return roomBroadcastSubscriber.getReceivedMessages();
    }

    @GetMapping("/room/broadcast/clear")
    public String clearBroadcastMessages() {
        roomBroadcastSubscriber.clear();
        return "已清理本机收到的房间广播消息";
    }

    @GetMapping("/node")
    public Map<String, Object> currentNode() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", roomBroadcastSubscriber.getCurrentNodeId());
        return result;
    }

    // --- 私有辅助方法 (Private Helpers) ---

    private String resolve(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private Map<String, Object> buildOnlineStatusMap(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("online", onlineStatusService.isOnline(userId));
        result.put("nodeId", onlineStatusService.getOnlineNode(userId));
        result.put("ttlSeconds", onlineStatusService.getOnlineTtlSeconds(userId));
        return result;
    }

    private Map<String, Object> buildUnreadResult(String userId, String sessionId, Long unread) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("sessionId", sessionId);
        result.put("unread", unread);
        result.put("totalUnread", unreadCountServiceV2.getTotalUnread(userId));
        return result;
    }

    private Map<String, Object> buildRoomMembersMap(String roomId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", roomId);
        result.put("members", roomBroadcastService.getRoomMembers(roomId));
        return result;
    }
}