package com.cl0.redisdemo.stage5;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 房间广播消息订阅者 (Pub/Sub Listener)
 * * 核心架构作用：
 * 在分布式 IM 系统中，假设有 A、B、C 三台服务器。
 * 当 A 服务器往 Redis 的 "im:room:broadcast" 频道发送一条消息时，A、B、C 三台服务器上的这个类都会被触发！
 * 它的任务是接收消息，并在真实生产环境中判断：“这条消息的目标用户，此时此刻连在我这台机器上吗？”如果是，就推送给前端。
 */
@Component
public class RoomBroadcastSubscriber {

    // 当前服务器节点的唯一标识 (例如：Node-A, Node-B)
    private final String currentNodeId;
    
    // 用于在内存中临时保存接收到的消息，方便前端查询演示。
    // 架构提醒：Redis 的 Pub/Sub 回调是在异步多线程环境下执行的，
    // 因此必须使用线程安全的集合 (synchronizedList) 来防止并发修改异常。
    private final List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造函数
     * 使用 Spring 的 @Value 注解从配置文件 (application.yml) 中读取当前节点的 ID。
     * 如果没配置，默认叫 "rtc-node-1"。
     * * 在真实的 K8s 或 Docker 集群部署中，每启动一个容器，这里都会被注入一个不同的随机 ID 或是 Pod Name。
     */
    public RoomBroadcastSubscriber(@Value("${rtc.node-id:rtc-node-1}") String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    /**
     * 消息处理的核心回调方法
     * 当 Redis 频道里有新消息时，Spring 底层会自动调用这个方法，把消息内容传进来。
     * * @param message 接收到的 JSON 格式的字符串消息
     */
    public void handleMessage(String message) {
        // 1. 记录日志：加上当前时间与当前节点 ID，方便在控制台肉眼分辨是哪台机器收到了消息
        String record = LocalDateTime.now() + " [" + currentNodeId + "] 收到房间广播消息：" + message;
        
        // 2. 存入内存列表，供 /stage5/pubsub/received 接口查询演示
        receivedMessages.add(record);
        System.out.println(record);

        /* * ==========================================
         * 架构师预留作业（真实生产环境中的逻辑应该写在这里）：
         * 1. 使用 Jackson 将 message 字符串反序列化为 Java 对象（包含 targetNodeId 和 targetUserId）。
         * 2. 判断：if (targetNodeId.equals(this.currentNodeId)) {
         * 3. 如果相等说明目标用户就挂在我这台机器的 WebSocket 上！
         * 4. 调用 WebSocket 推送服务，把内容发给 targetUserId 的手机/电脑。
         * 5. 如果不相等，说明是别的机器的活，直接无视并丢弃这条消息（Return 掉）。
         * ==========================================
         */
    }

    /**
     * 获取本机收到的所有消息记录
     * 架构提醒：遍历同步集合时，必须使用 synchronized 块锁住整个集合，
     * 否则在复制 (new ArrayList) 的过程中如果恰好有新消息到达，会抛出 ConcurrentModificationException。
     */
    public List<String> getReceivedMessages() {
        synchronized (receivedMessages) {
            return new ArrayList<>(receivedMessages);
        }
    }

    /**
     * 清理本机的消息记录
     */
    public void clear() {
        receivedMessages.clear();
    }

    /**
     * 获取当前节点的 ID
     */
    public String getCurrentNodeId() {
        return currentNodeId;
    }
}