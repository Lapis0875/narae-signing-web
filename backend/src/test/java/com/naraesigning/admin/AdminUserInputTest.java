package com.naraesigning.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AdminUserInputTest {
    @Test
    void canonicalizesAcceptedAsciiEmailAfterParsing() {
        assertThat(AdminEmail.parse("Operator.O'Neil+Desk@EXAMPLE-OPS.COM").value())
                .isEqualTo("operator.o'neil+desk@example-ops.com");
    }

    @Test
    void acceptsEmailAtLocalLabelAndTotalLengthBoundaries() {
        String domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60);
        assertThat(AdminEmail.parse("x@" + domain).value()).hasSize(254);
        assertThat(AdminEmail.parse("a".repeat(64) + "@b").value()).startsWith("a".repeat(64));
        assertThat(AdminEmail.parse("AZ09!#$%&'*+-/=?^_`{|}~@x").value())
                .isEqualTo("az09!#$%&'*+-/=?^_`{|}~@x");
    }

    @ParameterizedTest
    @MethodSource("invalidEmails")
    void rejectsEmailOutsideCanonicalGrammar(String email) {
        assertThatThrownBy(() -> AdminEmail.parse(email))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPasswordAtTwelveCodePointsAndSeventyTwoUtf8Bytes() {
        var password = ("가".repeat(20) + "a".repeat(12)).toCharArray();
        assertThat(new String(password).getBytes(StandardCharsets.UTF_8)).hasSize(72);

        assertThat(AdminPassword.parse(password).value()).containsExactly(password);
    }

    @Test
    void rejectsPasswordBelowTwelveCodePoints() {
        assertThatThrownBy(() -> AdminPassword.parse("12345678901".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPasswordAtSeventyThreeUtf8Bytes() {
        var password = ("가".repeat(21) + "a".repeat(10)).toCharArray();
        assertThat(new String(password).getBytes(StandardCharsets.UTF_8)).hasSize(73);

        assertThatThrownBy(() -> AdminPassword.parse(password))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<String> invalidEmails() {
        return Stream.of(
                " admin@example.com",
                "admin@example.com ",
                "ad min@example.com",
                "admin\n@example.com",
                "관리자@example.com",
                "admin@예시.com",
                "\"admin\"@example.com",
                "admin@@example.com",
                ".admin@example.com",
                "admin..desk@example.com",
                "admin.@example.com",
                "admin@-example.com",
                "admin@example-.com",
                "admin@example..com",
                "admin@example_.com",
                "admin(team)@example.com",
                "a".repeat(65) + "@example.com",
                "admin@" + "a".repeat(64) + ".com",
                "xy@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60));
    }
}
