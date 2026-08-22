package com.naraesigning.deletion;

import org.junit.jupiter.api.Test;

class BoardDeletingAccessContractTest {
    @Test
    void givenDeletingBoardWhenReadThroughCurrentContractsThenEverySurfaceDeniesAccess() {
        // Given
        var fixture = PersistedDeletionFixture.create("access-object");
        var access = DeletionAccessProbe.forState(fixture.store());

        // When / Then
        access.assertDenied();
    }
}
