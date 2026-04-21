package com.cl0.redisdemo.stage5;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * 房间广播订阅的底层配置类 (Redis Pub/Sub 引擎装配)
 * * 核心架构作用：
 * 这个类就像是服务器里的“无线电调频台”。
 * 它负责让 Spring Boot 启动时，自动建立与 Redis 的长连接，监听特定的频道 ("im:room:broadcast")，
 * 并规定当频道里有声音（消息）时，具体交给哪个类的哪个方法去处理。
 */
@Configuration
public class RoomBroadcastConfig {

    /**
     * 第一步：配置“消息适配器” (MessageListenerAdapter)
     * * 架构解析：适配器模式的应用。
     * Spring 底层并不知道我们的业务逻辑写在哪里。这个 Adapter 的作用就是告诉 Spring：
     * “请把收到的原生 Redis 消息，交给 `RoomBroadcastSubscriber` 这个 Bean 的 `handleMessage` 方法去处理。”
     * 这样一来，我们的 Subscriber 类就不需要去实现任何 Spring 的特定接口，保持了代码的纯粹性。
     *
     * @param subscriber 我们上一节写好的订阅处理类 (Spring 会自动注入)
     * @return 组装好的适配器
     */
    @Bean
    public MessageListenerAdapter roomBroadcastListenerAdapter(RoomBroadcastSubscriber subscriber) {
        // "handleMessage" 必须与 RoomBroadcastSubscriber 类中实际的方法名一模一样
        return new MessageListenerAdapter(subscriber, "handleMessage");
    }

    /**
     * 第二步：配置“消息监听容器” (RedisMessageListenerContainer)
     * * 架构解析：这是整个 Pub/Sub 机制的【心脏】。
     * 它会在后台默默维护与 Redis 的独立 TCP 长连接，并管理一个自己的线程池。
     * 当频道中有消息到达时，它会从线程池中拿出一个线程，去调用上面的 Adapter。
     * 这意味着，消息的接收和处理是【异步】的，绝对不会阻塞主线程的 Web 请求。
     *
     * @param connectionFactory            Redis 的连接工厂 (由 Spring Boot 自动提供)
     * @param roomBroadcastListenerAdapter 上面配置好的适配器
     * @return 启动就绪的监听容器
     */
    @Bean
    public RedisMessageListenerContainer roomBroadcastRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter roomBroadcastListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        
        // 1. 设置连接工厂（提供与 Redis 通信的基础网络能力）
        container.setConnectionFactory(connectionFactory);
        
        // 2. 将适配器与具体的频道 (Channel) 绑定在一起
        // 这里的 "im:room:broadcast" 必须和 RoomBroadcastService 中发消息时用的频道名完全一致！
        container.addMessageListener(
                roomBroadcastListenerAdapter,
                new ChannelTopic("im:room:broadcast")
        );
        
        return container;
    }
}