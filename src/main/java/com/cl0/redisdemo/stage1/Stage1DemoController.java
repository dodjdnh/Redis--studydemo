package com.cl0.redisdemo.stage1;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 1 综合演示控制器
 * 提供一个 HTTP GET 接口，用于一次性测试 IM 系统的所有核心 Redis 组件。
 */
@RestController
public class Stage1DemoController {

    // 注入你之前写的 5 个核心服务
    private final UserSessionStore userSessionStore;
    private final OfflineMessageQueue offlineMessageQueue;
    private final RoomMemberManager roomMemberManager;
    private final RecentContactsRanking recentContactsRanking;
    private final UnreadCountService unreadCountService;

    // 构造器注入 (Spring 推荐的做法，保证依赖的不可变性)
    public Stage1DemoController(UserSessionStore userSessionStore,
                                OfflineMessageQueue offlineMessageQueue,
                                RoomMemberManager roomMemberManager,
                                RecentContactsRanking recentContactsRanking,
                                UnreadCountService unreadCountService) {
        this.userSessionStore = userSessionStore;
        this.offlineMessageQueue = offlineMessageQueue;
        this.roomMemberManager = roomMemberManager;
        this.recentContactsRanking = recentContactsRanking;
        this.unreadCountService = unreadCountService;
    }

    /**
     * 运行测试用例
     * 访问路径: http://localhost:8080/stage1/demo
     */
    @GetMapping("/stage1/demo")
    public Map<String, Object> runDemo() {
        // 使用 LinkedHashMap 保证返回的 JSON 字段顺序与代码执行顺序一致
        Map<String, Object> result = new LinkedHashMap<>();

        // ==========================================
        // 1. Session 测试 (Hash 类型)
        // 模拟用户 10001 通过 Web 端登录，连接到了 rtc-node-1 节点
        // ==========================================
        userSessionStore.createSession("token-10001", "10001", "web", "rtc-node-1", "channel-abc");
        userSessionStore.refreshHeartbeat("token-10001"); // 模拟 Netty 收到一次心跳包
        result.put("session", userSessionStore.getSession("token-10001"));

        // ==========================================
        // 2. 离线消息队列测试 (List 类型)
        // 模拟用户 10002 处于离线状态，其他人给他发了两条消息
        // ==========================================
        offlineMessageQueue.pushOfflineMessage("10002", "{\"messageId\":\"msg-1\",\"content\":\"hello\"}");
        offlineMessageQueue.pushOfflineMessage("10002", "{\"messageId\":\"msg-2\",\"content\":\"world\"}");
        result.put("offlineCountBeforePop", offlineMessageQueue.countOfflineMessages("10002")); // 预期: 2
        
        // 模拟 10002 上线，拉取离线消息 (FIFO，先拿到 hello，再拿到 world)
        result.put("offlineMessages", offlineMessageQueue.popOfflineMessages("10002", 10));
        result.put("offlineCountAfterPop", offlineMessageQueue.countOfflineMessages("10002"));  // 预期: 0 (拉取后自动删除)

        // ==========================================
        // 3. 房间/群成员测试 (Set 类型)
        // 模拟 10001 和 10002 加入同一个聊天室 2001
        // ==========================================
        roomMemberManager.addMember("room-2001", "10001");
        roomMemberManager.addMember("room-2001", "10002");
        roomMemberManager.addMember("room-2001", "10002"); // 重复添加，测试 Set 的自动去重特性
        
        result.put("roomMemberCount", roomMemberManager.countMembers("room-2001")); // 预期: 2
        result.put("is10001InRoom", roomMemberManager.isMember("room-2001", "10001")); // 预期: true
        result.put("scanRoomMembers", roomMemberManager.scanMembers("room-2001", 10, 10)); // 游标扫描成员

        // ==========================================
        // 4. 最近联系人与消息序号测试 (ZSet & String 类型)
        // ==========================================
        // 模拟 10001 先在 2001 群里发了消息，又在 2002 群里发了消息
        recentContactsRanking.touchContact("10001", "session-2001");
        recentContactsRanking.touchContact("10001", "session-2002");
        // 预期: session-2002 排在第一位，因为它的时间戳更新
        result.put("recentContacts", recentContactsRanking.listRecentContacts("10001", 0, 10));
        
        // 模拟为 room-2001 连续生成两条消息的唯一序号
        result.put("messageSeq1", recentContactsRanking.nextMessageSeq("room-2001")); // 预期: 1
        result.put("messageSeq2", recentContactsRanking.nextMessageSeq("room-2001")); // 预期: 2

        // ==========================================
        // 5. 未读消息统计测试 (Hash 局部更新与删除)
        // ==========================================
        // 模拟 10001 收到了 2001 群的 2 条消息，和 2002 群的 1 条消息
        unreadCountService.increaseUnread("10001", "session-2001");
        unreadCountService.increaseUnread("10001", "session-2001");
        unreadCountService.increaseUnread("10001", "session-2002");
        
        result.put("unreadSession2001", unreadCountService.getUnread("10001", "session-2001")); // 预期: 2
        result.put("allUnread", unreadCountService.getAllUnread("10001")); // 预期: 返回包含 2001 和 2002 的 Map
        
        // 模拟 10001 点开了 2001 群的聊天窗口，未读数清零
        unreadCountService.clearUnread("10001", "session-2001");
        result.put("unreadSession2001AfterClear", unreadCountService.getUnread("10001", "session-2001")); // 预期: 0

        return result;
    }
}