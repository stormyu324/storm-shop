package com.javastorm.shop.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAccountFromAddressAfterFiveFailuresAndRecoversAfterWindow() {
        MutableClock clock = new MutableClock();
        LoginThrottle t = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.PER_ACCOUNT; i++) {
            assertThat(t.isBlocked("1.1.1.1", "admin")).isFalse();
            t.recordFailure("1.1.1.1", "admin");
        }
        assertThat(t.isBlocked("1.1.1.1", "admin")).isTrue();
        assertThat(t.isBlocked("1.1.1.1", "ADMIN ")).isTrue();
        assertThat(t.isBlocked("2.2.2.2", "admin")).isFalse();
        assertThat(t.isBlocked("1.1.1.1", "other")).isFalse();

        clock.now = clock.now.plus(LoginThrottle.WINDOW).plus(Duration.ofSeconds(1));
        assertThat(t.isBlocked("1.1.1.1", "admin")).isFalse();
    }

    @Test
    void blocksAddressGuessingManyAccounts() {
        LoginThrottle t = new LoginThrottle(new MutableClock());
        for (int i = 0; i < LoginThrottle.PER_ADDRESS; i++) {
            t.recordFailure("1.1.1.1", "user" + i);
        }
        assertThat(t.isBlocked("1.1.1.1", "fresh-name")).isTrue();
        assertThat(t.isBlocked("2.2.2.2", "fresh-name")).isFalse();
    }

    @Test
    void successClearsAccountFailures() {
        LoginThrottle t = new LoginThrottle(new MutableClock());
        for (int i = 0; i < LoginThrottle.PER_ACCOUNT - 1; i++) {
            t.recordFailure("1.1.1.1", "admin");
        }
        t.recordSuccess("1.1.1.1", "admin");
        t.recordFailure("1.1.1.1", "admin");
        assertThat(t.isBlocked("1.1.1.1", "admin")).isFalse();
    }

    @Test
    void capsTrackedKeysUnderRandomIpFlood() {
        LoginThrottle t = new LoginThrottle(new MutableClock(), 10);
        for (int i = 0; i < 30; i++) {
            t.recordFailure("10.0.0." + i, "admin");
        }
        assertThat(t.trackedKeys()).isLessThanOrEqualTo(10);
    }
}
