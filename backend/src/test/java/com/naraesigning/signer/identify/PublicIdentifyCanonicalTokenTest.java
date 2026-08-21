package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PublicIdentifyCanonicalTokenTest {
    @Test
    void identifyPackageHasNoDuplicateShareTokenParser() {
        // Given / When
        var duplicateParser = org.assertj.core.api.Assertions.catchThrowable(
                () -> Class.forName("com.naraesigning.signer.identify.PublicLinkToken"));

        // Then
        assertThat(duplicateParser).isInstanceOf(ClassNotFoundException.class);
    }
}
