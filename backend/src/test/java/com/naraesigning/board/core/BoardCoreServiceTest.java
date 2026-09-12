package com.naraesigning.board.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.AdminSessionContract;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
final class BoardCoreServiceTest {
    private static final BoardOwner OWNER_A = BoardOwner.synthetic(
            UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final BoardOwner OWNER_B = BoardOwner.synthetic(
            UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final String TITLE = "합성 행사 보드";

    private InMemoryBoardRepository repository;
    private BoardService boards;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBoardRepository();
        boards = new BoardService(repository, crypto(), directTransactions());
    }

    @Test
    void createStoresRecoverableCurrentShareToken_whenOwnerIsAuthenticated() {
        // Given: synthetic authenticated owner A.

        // When: owner A creates one board.
        var created = boards.create(OWNER_A, TITLE);

        // Then: token is private, random, current, encrypted at rest, and hash-resolvable.
        assertThat(created.shareToken()).hasSize(43).doesNotContain("=");
        assertThat(created.board().status()).isEqualTo("설정 중");
        assertThat(created.board().signatureInkColor()).isEqualTo(SignatureInkColor.BLACK);
        assertThat(repository.stored(created.board().id()).signatureInkColor()).isEqualTo(SignatureInkColor.BLACK);
        assertThat(boards.currentShare(OWNER_A, created.board().id())).isEqualTo(created.shareToken());
        assertThat(repository.findPublicByLookupHash(BoardShareToken.lookupHash(created.shareToken())))
                .isPresent();
        assertThat(repository.stored(created.board().id()).share().encryptedToken().ciphertext())
                .isNotEqualTo(created.shareToken().getBytes());
        System.out.println("QA board_create=true current_share_owner_only=true hash_lookup=true");
    }

    @Test
    void reissueInvalidatesOldToken_whenOwnerReissuesCurrentShare() {
        // Given: owner A board and its first private token.
        var created = boards.create(OWNER_A, TITLE);
        var oldToken = created.shareToken();

        // When: owner A reissues the share identity.
        var reissued = boards.reissueShare(OWNER_A, created.board().id());

        // Then: version increments once and only the new token resolves.
        assertThat(reissued.version()).isEqualTo(2);
        assertThat(reissued.shareToken()).isNotEqualTo(oldToken);
        assertThat(boards.findPublic(oldToken)).isEmpty();
        assertThat(boards.findPublic(reissued.shareToken())).isPresent();
        assertThat(boards.currentShare(OWNER_A, created.board().id()))
                .isEqualTo(reissued.shareToken());
        System.out.println("QA share_reissue=true old_hash_miss=true version_increment=1");
    }

    @Test
    void ownerIsolationUsesSameUnavailableOutcome_whenBoardIsUnknownOrOwnedByAnotherAdmin() {
        // Given: one owner-A board and an unrelated UUID.
        var boardId = boards.create(OWNER_A, TITLE).board().id();
        var unknownId = UUID.fromString("33333333-3333-4333-8333-333333333333");

        // When/Then: owner B and unknown UUID get the same generic failure.
        assertUnavailable(() -> boards.detail(OWNER_B, boardId));
        assertUnavailable(() -> boards.rename(OWNER_B, boardId, "탈취 시도"));
        assertUnavailable(() -> boards.currentShare(OWNER_B, boardId));
        assertUnavailable(() -> boards.reissueShare(OWNER_B, boardId));
        assertUnavailable(() -> boards.detail(OWNER_A, unknownId));
        System.out.println("QA owner_b_enumeration=generic_unavailable");
    }

    @Test
    void deletingBoardsAreExcluded_whenOwnerListsOrFetchesBoards() {
        // Given: one visible board and one internally deleting board.
        var visible = boards.create(OWNER_A, "보이는 보드").board();
        var deleting = boards.create(OWNER_A, "삭제 중인 보드").board();
        repository.markDeleting(deleting.id());

        // When: owner A lists boards.
        var listed = boards.list(OWNER_A);

        // Then: only visible board remains and deleting detail is unavailable.
        assertThat(listed).extracting(BoardView::id).containsExactly(visible.id());
        assertUnavailable(() -> boards.detail(OWNER_A, deleting.id()));
    }

    @Test
    void titleUsesUnicodeCodePointBounds_whenCreatedOrRenamed() {
        // Given: boundary titles using supplementary Unicode code points.
        var oneCodePoint = "\uD83D\uDE80";
        var oneHundredTwenty = oneCodePoint.repeat(120);
        var oneHundredTwentyOne = oneCodePoint.repeat(121);
        var board = boards.create(OWNER_A, oneCodePoint).board();

        // When: valid maximum title is applied.
        var renamed = boards.rename(OWNER_A, board.id(), oneHundredTwenty);

        // Then: 120 code points pass; empty, blank, and 121 code points fail generically.
        assertThat(renamed.title()).isEqualTo(oneHundredTwenty);
        assertInvalidTitle(() -> boards.create(OWNER_A, ""));
        assertInvalidTitle(() -> boards.create(OWNER_A, "   "));
        assertInvalidTitle(() -> boards.rename(OWNER_A, board.id(), oneHundredTwentyOne));
        assertInvalidTitle(() -> boards.rename(OWNER_A, board.id(), "\ud800"));
        System.out.println("QA title_120=accepted title_121=validation_error");
    }

    @Test
    void apiBoundaryMapsPersistedLifecycleToKoreanProductLabels() {
        // Given: every externally visible persisted lifecycle.

        // When/Then: mapping occurs only in BoardView boundary records.
        assertThat(BoardView.statusLabel(BoardStatus.DRAFT)).isEqualTo("설정 중");
        assertThat(BoardView.statusLabel(BoardStatus.OPEN)).isEqualTo("서명 진행");
        assertThat(BoardView.statusLabel(BoardStatus.CLOSED)).isEqualTo("마감/보관");
        assertThatThrownBy(() -> BoardView.statusLabel(BoardStatus.DELETING))
                .isInstanceOf(BoardUnavailableException.class)
                .hasMessage("BOARD_UNAVAILABLE");
    }

    @Test
    void sessionOwnerComesFromAdminSessionContract() {
        // Given: session issued through shared admin-session contract.
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, OWNER_A.id(), Instant.parse("2026-08-20T00:00:00Z"));

        // When: board owner is resolved at authenticated boundary.
        var owner = BoardOwner.fromSession(session);

        // Then: contract principal is sole identity source.
        assertThat(owner).isEqualTo(OWNER_A);
    }

    @Test
    void responsesAndLogsContainNoSharePlaintextOrStorageMetadata(CapturedOutput output) {
        // Given: owner board with private share identity.
        var created = boards.create(OWNER_A, TITLE);
        var rawToken = created.shareToken();

        // When: safe list/detail and malformed-token public lookup execute.
        var listText = boards.list(OWNER_A).toString();
        var detailText = boards.detail(OWNER_A, created.board().id()).toString();
        var invalid = boards.findPublic("not-a-valid-token");

        // Then: outputs/logs reveal neither raw secret nor storage/owner internals.
        assertThat(invalid).isEmpty();
        assertThat(listText).doesNotContain(rawToken, "owner", "ciphertext", "nonce", "lookupHash");
        assertThat(detailText).doesNotContain(rawToken, "owner", "ciphertext", "nonce", "lookupHash");
        assertThat(output.getAll()).doesNotContain(rawToken, TITLE, OWNER_A.id().toString(),
                "ciphertext", "nonce", "lookupHash");
        System.out.println("QA plaintext_probe=clean invalid_link=generic_miss");
    }

    private static VersionedCryptoService crypto() {
        var key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        return new VersionedCryptoService(Map.of(1, key), 1);
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BoardUnavailableException.class)
                .hasMessage("BOARD_UNAVAILABLE");
    }

    private static void assertInvalidTitle(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(InvalidBoardTitleException.class)
                .hasMessage("INVALID_BOARD_TITLE");
    }

}
