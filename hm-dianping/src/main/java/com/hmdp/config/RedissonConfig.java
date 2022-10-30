package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zhinushannan
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 这里使用的是但节点地址，也可以使用 config.useClusterServers() 添加集群地址
        config.useSingleServer().setAddress("redis://172.72.0.53:6379");
        return Redisson.create(config);
    }
    
}
