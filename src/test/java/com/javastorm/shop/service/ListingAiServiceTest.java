package com.javastorm.shop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javastorm.shop.service.ListingAiService.EnglishListing;
import com.javastorm.shop.service.ListingAiService.ListingInput;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用本地假服务器代替 Claude API，检查发出的请求和对返回结果的处理。 */
class ListingAiServiceTest {

    static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastBeta = new AtomicReference<>();
    volatile int status = 200;
    volatile String responseBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastBody.set(JSON.readTree(exchange.getRequestBody()));
            lastBeta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-should-retry", "false");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ListingAiService service() {
        return new ListingAiService("test-key", "claude-opus-5-5", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    static String message(String stopReason, Object listing) throws IOException {
        String text = JSON.writeValueAsString(listing);
        return JSON.writeValueAsString(Map.of(
                "id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                "content", List.of(Map.of("type", "text", "text", text)),
                "stop_reason", stopReason,
                "usage", Map.of("input_tokens", 100, "output_tokens", 200)));
    }

    static ListingInput input() {
        return new ListingInput("纯棉圆领T恤 白色 M码", "服装", "100% 纯棉，柔软透气", "StormWear", "White", "M",
                "100% Cotton", 180, "30 x 25 x 3", null);
    }

    @Test
    void sendsStructuredRequestAndParsesListing() throws Exception {
        responseBody = message("end_turn", Map.of(
                "title", "StormWear Classic Cotton Crew Neck T-Shirt, White, M",
                "bulletPoints", List.of("SOFT: a", "BREATHABLE: b", "CLASSIC FIT: c", "EASY CARE: d", "VERSATILE: e", "EXTRA: f"),
                "description", "A soft everyday tee."));

        EnglishListing l = service().generate(input());

        assertThat(l.title()).isEqualTo("StormWear Classic Cotton Crew Neck T-Shirt, White, M");
        assertThat(l.bulletPoints()).hasSize(5).first().isEqualTo("SOFT: a");
        assertThat(l.description()).isEqualTo("A soft everyday tee.");

        JsonNode body = lastBody.get();
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("fallbacks").asText()).isEqualTo("default");
        assertThat(lastBeta.get()).contains("server-side-fallback-2026-07-01");
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("properties").has("bulletPoints")).isTrue();
        assertThat(body.path("system").toString()).contains("exactly 5 items");
        String user = body.path("messages").get(0).path("content").toString();
        assertThat(user).contains("纯棉圆领T恤").contains("Material: 100% Cotton").contains("180 g");
    }

    @Test
    void longTextIsTrimmedToFieldLimits() {
        EnglishListing cleaned = ListingAiService.clean(new EnglishListing(
                "Word ".repeat(60), List.of("  A:   spaced   out  ", "", "B: " + "x".repeat(400)), null));
        assertThat(cleaned.title().length()).isLessThanOrEqualTo(200);
        assertThat(cleaned.title()).doesNotEndWith(" ");
        assertThat(cleaned.bulletPoints()).hasSize(2);
        assertThat(cleaned.bulletPoints().get(0)).isEqualTo("A: spaced out");
        assertThat(cleaned.bulletPoints().get(1)).hasSize(250);
    }

    @Test
    void refusalAndAuthErrorsBecomeReadableMessages() throws Exception {
        responseBody = message("refusal", Map.of("title", "", "bulletPoints", List.of(), "description", ""));
        assertThatThrownBy(() -> service().generate(input()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("拒绝");

        status = 401;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        assertThatThrownBy(() -> service().generate(input()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ANTHROPIC_API_KEY 无效");
    }

    @Test
    void disabledWithoutApiKey() {
        ListingAiService off = new ListingAiService("", "claude-opus-5-5", "");
        assertThat(off.isEnabled()).isFalse();
        assertThatThrownBy(() -> off.generate(input())).hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
