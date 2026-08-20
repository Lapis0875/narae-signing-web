package com.naraesigning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NaraeSigningApplicationTest {
    @Test
    void applicationEntryPointLoads() {
        assertDoesNotThrow(() -> Class.forName("com.naraesigning.NaraeSigningApplication"));
    }
}
