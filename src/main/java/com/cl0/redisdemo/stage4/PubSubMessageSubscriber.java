package com.cl0.redisdemo.stage4;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 发布/订阅 (Pub/Sub) 模式的消息订阅者（消费者）。
 * 该类负责监听并处理从 Redis 频道推送过来的消息。
 * 在 Spring Data Redis 中，通常会配合 MessageListenerAdapter 将这个类注册为消息处理器。
 */
@Component
public class PubSubMessageSubscriber {

    /**
     * 存储接收到的消息历史记录。
     * 由于 Redis 的消息订阅通常是在多线程环境下异步回调的（如 Spring 的 TaskExecutor），
     * 这里使用 Collections.synchronizedList 来保证基本的线程安全，防止并发写入导致数据丢失。
     */
    private final List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

    /**
     * 消息处理的入口方法。
     * 当有新消息发布到订阅的频道时，框架会通过反射或接口调用此方法。
     *
     * @param message 从 Redis 频道接收到的消息内容（对应发送端的 messageJson）
     */
    public void handleMessage(String message) {
        // 拼接时间戳与消息内容，生成本地记录
        String record = LocalDateTime.now() + " 收到 Pub/Sub 消息：" + message;
        
        // 将记录添加到线程安全的列表中
        receivedMessages.add(record);
        
        // 打印到控制台（实际项目中建议替换为日志框架）
        System.out.println(record);
    }

    /**
     * 获取当前接收到的所有消息历史。
     * * @return 包含所有历史消息的新列表（防御性拷贝）
     */
    public List<String> getReceivedMessages() {
        // 【关键点】：虽然 receivedMessages 是 synchronizedList，它的 add/remove 是线程安全的，
        // 但对其进行迭代或基于它创建新集合（如 new ArrayList<>(...)）时，不是原子操作。
        // 因此必须使用 synchronized 块锁住对象本身，防止在拷贝过程中有新消息写入导致 ConcurrentModificationException。
        synchronized (receivedMessages) {
            return new ArrayList<>(receivedMessages);
        }
    }

    /**
     * 清空当前的消息历史记录。
     * synchronizedList 的 clear() 方法本身是线程安全的。
     */
    public void clear() {
        receivedMessages.clear();
    }
}