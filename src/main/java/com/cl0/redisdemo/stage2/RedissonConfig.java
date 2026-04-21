package com.cl0.redisdemo.stage2;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类。
 * 作用：初始化 RedissonClient 实例并将其注册到 Spring 容器中。
 * 有了这个配置，你就可以在代码里直接 @Autowired RedissonClient 来使用分布式锁等高级功能了。
 */
@Configuration // 告诉 Spring 这是一个配置类，Spring 启动时会解析并执行里面的 @Bean 方法
public class RedissonConfig {

    /**
     * 声明一个由 Spring 管理的 Bean。
     * destroyMethod = "shutdown"：这是一个非常好的习惯！
     * 它告诉 Spring 容器在关闭/销毁应用时，自动调用 redissonClient 的 shutdown() 方法，
     * 从而优雅地释放所有的 Redis 连接资源，防止内存泄漏。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            // 使用 @Value 注解从 application.yml/properties 中读取 Redis 配置
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            // 冒号后面的空字符串代表默认值：如果配置文件里没配密码，就当做空字符串处理
            @Value("${spring.data.redis.password:}") String password,
            // 默认连接第 0 号数据库
            @Value("${spring.data.redis.database:0}") int database) {

        // 1. 创建 Redisson 配置对象
        Config config = new Config();

        // 2. 拼接完整的 Redis 连接地址 (Redisson 要求必须以 redis:// 或 rediss:// 开头)
        String address = "redis://" + host + ":" + port;

        // 3. 配置单机模式 (如果是集群模式，这里要改为 useClusterServers())
        config.useSingleServer()
              .setAddress(address)
              .setDatabase(database);

        // 4. 处理密码配置
        // 只有当密码不为 null 且不是纯空格/空字符串时，才去设置密码
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }

        // 5. 根据配置对象，创建并返回完整的 Redisson 客户端实例
        return Redisson.create(config);
    }
}