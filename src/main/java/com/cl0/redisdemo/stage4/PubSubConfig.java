package com.cl0.redisdemo.stage4;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis 发布/订阅 (Pub/Sub) 的核心配置类。
 * 该类负责组装消息监听容器，将“消息消费者”与“Redis 频道”绑定在一起。
 * * @Configuration 注解表明这是一个 Spring 配置类，Spring 启动时会解析并注册其中的 @Bean。
 */
@Configuration
public class PubSubConfig {

    /**
     * 配置消息监听适配器 (MessageListenerAdapter)。
     * 它的作用是作为代理，将 Redis 接收到的底层消息（字节序列）转换后，
     * 通过反射路由给指定的业务处理类和方法。
     *
     * @param subscriber 我们自定义的消息处理类（即之前的 PubSubMessageSubscriber）
     * @return 包装好的适配器对象
     */
    @Bean
    public MessageListenerAdapter messageListenerAdapter(PubSubMessageSubscriber subscriber) {
        // 第一个参数是实际处理消息的对象实例
        // 第二个参数 "handleMessage" 是约定的方法名，当收到消息时，适配器会通过反射调用该方法
        return new MessageListenerAdapter(subscriber, "handleMessage");
    }

    /**
     * 配置 Redis 消息监听容器 (RedisMessageListenerContainer)。
     * 这是 Spring Data Redis 异步消息接收的核心组件（类似于大管家）。
     * 它负责管理 Redis 连接、线程池，并分发消息给对应的适配器。
     *
     * @param connectionFactory      Redis 连接工厂（Spring Boot 自动配置并注入）
     * @param messageListenerAdapter 上方配置好的监听适配器
     * @return 组装完毕的监听容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter) {

        // 1. 创建容器实例
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        
        // 2. 设置 Redis 连接工厂，使其能够与 Redis 服务器通信
        container.setConnectionFactory(connectionFactory);
        
        // 3. 将【监听器】与【频道】进行绑定
        // 这里明确指定了：来自 PUSH_CHANNEL ("im:push") 频道的消息，统统交给 messageListenerAdapter 处理
        // 注意：这里使用的是 ChannelTopic (精确匹配频道名)。
        // 如果想匹配多个频道 (如 "im:*")，可以使用 PatternTopic。
        container.addMessageListener(
                messageListenerAdapter,
                new ChannelTopic(PubSubMessenger.PUSH_CHANNEL)
        );
        
        return container;
    }
}