package com.javastorm.shop.service;

import com.javastorm.shop.repository.MemberAddressRepository;
import com.javastorm.shop.repository.MemberChallengeRepository;
import com.javastorm.shop.repository.MemberFavoriteRepository;
import com.javastorm.shop.repository.MemberRepository;
import com.javastorm.shop.repository.OrderAccessRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 集群部署（开启 Redis）时，会员登录和订单查询的验证码限流由各节点共用 Redis 计数，且不设全站每日上限。 */
class CodeRequestRedisLimitTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(redis);
        return p;
    }

    private static OrderMailService mailAvailable() {
        OrderMailService mail = mock(OrderMailService.class);
        when(mail.available()).thenReturn(true);
        return mail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberCodeRequestsAreCountedInRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0L, 1L);
        MemberChallengeRepository challenges = mock(MemberChallengeRepository.class);
        MemberService members = new MemberService(mock(MemberRepository.class), challenges,
                mock(MemberAddressRepository.class), mock(MemberFavoriteRepository.class),
                mock(ProductRepository.class), mailAvailable(), true, provider(redis));

        assertThat(members.requestCode("a@example.com", "203.0.113.7")).isNotBlank();
        // 第二次 Redis 判定超限（可能是别的节点计的数）：本节点也要拒绝
        assertThatThrownBy(() -> members.requestCode("a@example.com", "203.0.113.7"))
                .isInstanceOf(ResponseStatusException.class);

        // 键按“验证码类型 + 来源 IP”区分；最后一个参数 "0" 表示不检查全站每日上限
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class),
                eq(List.of("storm:rl:member-code:client:203.0.113.7",
                        "storm:rl:member-code:day:" + java.time.LocalDate.now())),
                anyString(), anyString(), eq("5"), anyString(), anyString(), eq("0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void orderLookupCodeRequestsAreCountedInRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByOrderNo(anyString())).thenReturn(Optional.empty());
        OrderAccessService access = new OrderAccessService(mock(OrderAccessRepository.class), orders,
                mailAvailable(), true, provider(redis));

        assertThatThrownBy(() -> access.request("202611270000000001", "a@example.com", "203.0.113.8"))
                .isInstanceOf(ResponseStatusException.class);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("storm:rl:order-code:client:203.0.113.8",
                        "storm:rl:order-code:day:" + java.time.LocalDate.now())),
                anyString(), anyString(), eq("5"), anyString(), anyString(), eq("0"));
    }
}
