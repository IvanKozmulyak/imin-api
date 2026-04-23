package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Profile("!test")
@Configuration
public class RateLimitConfig {

    @Value("${imin.ratelimit.login.capacity}")
    private int loginCapacity;
    @Value("${imin.ratelimit.login.window-minutes}")
    private int loginWindow;
    @Value("${imin.ratelimit.ai-concept.capacity}")
    private int aiCapacity;
    @Value("${imin.ratelimit.ai-concept.window-minutes}")
    private int aiWindow;

    @Bean
    public RedisClient redisClient(@Value("${spring.data.redis.host}") String host,
                                   @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> conn) {
        return LettuceBasedProxyManager.builderFor(conn).build();
    }

    @Bean
    public RateLimiter rateLimiter(ProxyManager<String> proxy) {
        Map<String, BucketConfiguration> configs = new ConcurrentHashMap<>();
        configs.put("login", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(loginCapacity, Duration.ofMinutes(loginWindow)))
                .build());
        configs.put("ai-concept", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(aiCapacity, Duration.ofMinutes(aiWindow)))
                .build());

        return (bucketName, key) -> {
            BucketConfiguration cfg = configs.get(bucketName);
            if (cfg == null) throw new IllegalArgumentException("Unknown bucket " + bucketName);
            String redisKey = "ratelimit:" + bucketName + ":" + key;
            Bucket bucket = proxy.builder().build(redisKey, () -> cfg);
            if (!bucket.tryConsume(1)) throw ApiException.rateLimited();
        };
    }
}
