package com.naraesigning.board.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcOperations;

final class BoardCoreJdbcRepositoryTest {
    @Test
    void ownerQueriesCarryOwnerAndDeletingPredicates() {
        // Given: JDBC repository and synthetic owner-scoped board ID.
        var jdbc = mock(JdbcOperations.class);
        var repository = new JdbcBoardRepository(jdbc);
        var owner = BoardOwner.synthetic(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        var boardId = UUID.fromString("33333333-3333-4333-8333-333333333333");

        // When: list, detail, rename, and share-lock reads are prepared.
        repository.list(owner);
        repository.find(owner, boardId);
        repository.rename(owner, boardId, new BoardTitle("합성 제목"));
        repository.lockShare(owner, boardId);

        // Then: every SQL boundary scopes owner and excludes deleting rows.
        assertThat(mockingDetails(jdbc).getInvocations())
                .extracting(invocation -> (String) invocation.getArgument(0))
                .allSatisfy(sql -> assertThat(sql)
                        .contains("owner_id = ?")
                        .contains("status <> 'DELETING'"));
    }

    @Test
    void publicLookupPassesOnlySha256HashToJdbc() {
        // Given: SHA-256 lookup bytes and JDBC repository.
        var jdbc = mock(JdbcOperations.class);
        var repository = new JdbcBoardRepository(jdbc);
        var lookupHash = new byte[32];
        Arrays.fill(lookupHash, (byte) 0x4a);

        // When: public share identity lookup is prepared.
        repository.findPublicByLookupHash(lookupHash);

        // Then: query uses hash column and receives no raw-token string argument.
        Invocation invocation = mockingDetails(jdbc).getInvocations().iterator().next();
        assertThat((String) invocation.getArgument(0))
                .contains("share_token_lookup_hash = ?")
                .contains("status <> 'DELETING'");
        assertThat(invocation.getArguments()).contains(lookupHash);
        assertThat(Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length))
                .noneMatch(String.class::isInstance);
    }
}
