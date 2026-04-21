package com.cl0.redisdemo.stage4;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Stage4 演示控制器：负责暴露 RESTful 接口，方便通过浏览器或 Postman 测试 Redis 消息功能。
 */
@RestController
public class Stage4DemoController {

    private final PubSubMessenger pubSubMessenger;
    private final PubSubMessageSubscriber subscriber;
    private final StreamProducer streamProducer;
    private final StreamConsumer streamConsumer;
    private final StreamVsPubSubDemo streamVsPubSubDemo;

    /**
     * 构造函数注入：引入了消息发送、接收、消费以及对比演示的所有相关组件。
     */
    public Stage4DemoController(PubSubMessenger pubSubMessenger,
                                PubSubMessageSubscriber subscriber,
                                StreamProducer streamProducer,
                                StreamConsumer streamConsumer,
                                StreamVsPubSubDemo streamVsPubSubDemo) {
        this.pubSubMessenger = pubSubMessenger;
        this.subscriber = subscriber;
        this.streamProducer = streamProducer;
        this.streamConsumer = streamConsumer;
        this.streamVsPubSubDemo = streamVsPubSubDemo;
    }

    // ==========================================
    // 1. Pub/Sub (发布/订阅) 模块接口
    // ==========================================

    /**
     * 发送一条订阅消息。
     * URL 示例：/stage4/pubsub/send?targetUserId=10001&content=hello
     */
    @GetMapping("/stage4/pubsub/send")
    public String sendPubSub(String targetUserId, String content) {
        // 参数校验与默认值处理
        String resolvedUserId = (targetUserId == null || targetUserId.isBlank()) ? "10001" : targetUserId;
        String resolvedContent = (content == null || content.isBlank()) ? "hello pubsub" : content;

        Long subscriberCount = pubSubMessenger.publishPushMessage(resolvedUserId, resolvedContent);
        return "已发布 Pub/Sub 消息，当前实时在线的订阅者数量：" + subscriberCount;
    }

    /**
     * 查看本机内存中记录的已收到的 Pub/Sub 消息。
     */
    @GetMapping("/stage4/pubsub/received")
    public List<String> receivedPubSub() {
        return subscriber.getReceivedMessages();
    }

    /**
     * 清理本机记录的消息列表。
     */
    @GetMapping("/stage4/pubsub/clear")
    public String clearPubSub() {
        subscriber.clear();
        return "已清理本机收到的 Pub/Sub 消息记录";
    }

    // ==========================================
    // 2. Stream (持久化消息流) 模块接口
    // ==========================================

    /**
     * 向用户的离线消息队列（Stream）中写入一条数据。
     */
    @GetMapping("/stage4/stream/add")
    public String addStreamMessage(String userId, String messageId, String content) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10002" : userId;
        String resolvedMessageId = (messageId == null || messageId.isBlank()) ? "msg-1" : messageId;
        String resolvedContent = (content == null || content.isBlank()) ? "hello stream" : content;

        String recordId = streamProducer.addOfflineMessage(
                resolvedUserId,
                resolvedMessageId,
                resolvedContent
        );

        return "已写入 Stream，Redis 生成的记录 ID=" + recordId;
    }

    /**
     * 获取指定用户 Stream 队列中积压的消息总数。
     */
    @GetMapping("/stage4/stream/size")
    public Long streamSize(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10002" : userId;
        return streamProducer.streamSize(resolvedUserId);
    }

    /**
     * 读取并确认（ACK）消息。
     * 对应你之前看到的 readAndAckOfflineMessages 方法。
     */
    @GetMapping("/stage4/stream/read")
    public List<String> readStream(String userId, Integer count) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10002" : userId;
        int resolvedCount = (count == null || count <= 0) ? 10 : count;
        
        return streamConsumer.readAndAckOfflineMessages(resolvedUserId, resolvedCount);
    }

    /**
     * 查看当前用户有多少消息处于“已读取但未确认（Pending）”的状态。
     */
    @GetMapping("/stage4/stream/pending")
    public Long pendingStream(String userId) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10002" : userId;
        return streamConsumer.pendingCount(resolvedUserId);
    }

    // ==========================================
    // 3. 综合对比模块接口
    // ==========================================

    /**
     * 一键对比两种模式。
     */
    @GetMapping("/stage4/compare")
    public Map<String, Object> compare(String userId, String content) {
        String resolvedUserId = (userId == null || userId.isBlank()) ? "10002" : userId;
        String resolvedContent = (content == null || content.isBlank()) ? "hello compare" : content;
        
        return streamVsPubSubDemo.compare(resolvedUserId, resolvedContent);
    }
}