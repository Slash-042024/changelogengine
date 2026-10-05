package com.changelogengine.service;

import com.changelogengine.dto.AuditJob;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Serialized test storage; no Redis server is needed for unit tests. */
class RedisTestStore {
    final Map<String, byte[]> values = new ConcurrentHashMap<>();
    final Map<String, Duration> expirations = new ConcurrentHashMap<>();
    final RedisTemplate<String, AuditJob> redis;
    final ValueOperations<String, AuditJob> operations;

    @SuppressWarnings("unchecked")
    RedisTestStore() {
        redis = mock(RedisTemplate.class);
        operations = mock(ValueOperations.class);
        var serializer = new JdkSerializationRedisSerializer();
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenAnswer(call -> {
            byte[] stored = values.get(call.getArgument(0));
            return stored == null ? null : serializer.deserialize(stored);
        });
        doAnswer(call -> {
            String key = call.getArgument(0);
            values.put(key, serializer.serialize(call.getArgument(1)));
            expirations.put(key, call.getArgument(2));
            return null;
        }).when(operations).set(anyString(), any(AuditJob.class), any(Duration.class));
        doAnswer(call -> {
            String key = call.getArgument(0);
            Expiration expiration = call.getArgument(2);
            if (!expiration.isKeepTtl()) {
                throw new AssertionError("Recovery must preserve the TTL");
            }
            values.put(key, serializer.serialize(call.getArgument(1)));
            return null;
        }).when(operations).set(anyString(), any(AuditJob.class), any(Expiration.class));
        when(redis.exec()).thenReturn(List.of(true));
        when(redis.scan(any(ScanOptions.class))).thenAnswer(call -> {
            var iterator = List.copyOf(values.keySet()).iterator();
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(ignored -> iterator.hasNext());
            when(cursor.next()).thenAnswer(ignored -> iterator.next());
            return cursor;
        });
        when(redis.execute(any(SessionCallback.class))).thenAnswer(call -> {
            synchronized (values) {
                return ((SessionCallback<?>) call.getArgument(0)).execute(redis);
            }
        });
    }
}
