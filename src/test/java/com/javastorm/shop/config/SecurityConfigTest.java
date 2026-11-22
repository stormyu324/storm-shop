package com.javastorm.shop.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void productionRejectsDefaultOrShortPassword() {
        assertThatThrownBy(() -> SecurityConfig.checkPassword("admin123", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.checkPassword("short", true)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> SecurityConfig.checkPassword("a-Strong-Passw0rd", true)).doesNotThrowAnyException();
        // 开发环境只警告，不阻止启动
        assertThatCode(() -> SecurityConfig.checkPassword("admin123", false)).doesNotThrowAnyException();
    }
}
