package com.naraesigning.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.crypto.VersionedCryptoService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(OutputCaptureExtension.class)
final class RosterServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private InMemoryRosterRepository repository;
    private VersionedCryptoService crypto;
    private RosterService roster;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRosterRepository();
        crypto = crypto();
        roster = new RosterService(repository, crypto, snapshotTransactions(repository));
    }

    @Test
    void retainsUuidCiphertextAndSlotState_whenByteExactIdentityRemainsInDraftReplacement() {
        var exact = new RosterIdentity(" 소속사 ", "직책\t", "이름 ");
        var created = roster.replace(BOARD_ID, List.of(exact)).getFirst();
        repository.place(created.id());
        var before = repository.storedEntry(created.id());

        var replaced = roster.replace(BOARD_ID, List.of(exact, new RosterIdentity("", "", "새 이름")));

        var retained = repository.storedEntry(created.id());
        assertThat(replaced).extracting(RosterEntry::identity).containsExactly(exact,
                new RosterIdentity("", "", "새 이름"));
        assertThat(retained.id()).isEqualTo(before.id());
        assertThat(retained.identity().ciphertext()).containsExactly(before.identity().ciphertext());
        assertThat(retained.slot()).isEqualTo(before.slot());
        assertThat(retained.slot().placementStatus()).isEqualTo("PLACED");
        assertThat(replaced.get(1).slot().placementStatus()).isEqualTo("UNPLACED");
        System.out.println("QA uuid_slot_retention=true encrypted_identity=true unplaced_slot=true");
    }

    @Test
    void computesExactBoardBoundHmac_andCiphertextContainsNoPlaintext() {
        var identity = new RosterIdentity("정확한 소속", "정확한 직책", "정확한 이름");

        var created = roster.create(BOARD_ID, identity);

        var stored = repository.storedEntry(created.id());
        assertThat(stored.hmac()).containsExactly(crypto.identityHmac(
                BOARD_ID.toString(), identity.organization(), identity.job(), identity.name()));
        assertThat(new String(stored.identity().ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(identity.organization(), identity.job(), identity.name());
    }

    @Test
    void openAllowsDirectMutationOnlyForUnsubmittedPeople() {
        var created = roster.create(BOARD_ID, new RosterIdentity("A", "B", "C"));
        repository.open();

        var changed = roster.update(BOARD_ID, created.id(), new RosterIdentity("A2", "B2", "C2"));
        roster.delete(BOARD_ID, changed.id());
        var submitted = roster.create(BOARD_ID, new RosterIdentity("D", "E", "F"));
        repository.markSubmitted(submitted.id());

        assertThatThrownBy(() -> roster.update(BOARD_ID, submitted.id(), new RosterIdentity("X", "Y", "Z")))
                .isInstanceOf(RosterUnavailableException.class);
        assertThatThrownBy(() -> roster.delete(BOARD_ID, submitted.id()))
                .isInstanceOf(RosterUnavailableException.class);
        assertThatThrownBy(() -> roster.replace(BOARD_ID, List.of(new RosterIdentity("X", "Y", "Z"))))
                .isInstanceOf(RosterUnavailableException.class);
    }

    @Test
    void malformedOrPersistenceFailureChangesNothing_andLogsNoPlaintext(CapturedOutput output) {
        var secret = new RosterIdentity("비밀소속", "비밀직책", "비밀이름");
        roster.replace(BOARD_ID, List.of(secret, new RosterIdentity("둘째", "직책", "이름")));
        var before = repository.observableGraph(BOARD_ID);
        repository.failAfterFirstReplacementMutation();

        assertThatThrownBy(() -> roster.replace(BOARD_ID, List.of(new RosterIdentity("새소속", "새직책", "새이름"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.replacementMutationCount()).isOne();
        assertThat(repository.observableGraph(BOARD_ID)).containsExactlyElementsOf(before);
        assertThat(output.getAll()).doesNotContain("비밀소속", "비밀직책", "비밀이름", "새소속", "새이름");
        System.out.println("QA after_first_mutation=1 graph_exact_after_rollback=true plaintext_log=false");
    }

    @Test
    void rejectsDuplicateBeforeReplacement() {
        var identity = new RosterIdentity("A", "B", "C");
        assertThatThrownBy(() -> roster.replace(BOARD_ID, List.of(identity, identity)))
                .isInstanceOf(RosterInputException.class).hasMessage("DUPLICATE_IDENTITY");
        assertThat(roster.list(BOARD_ID)).isEmpty();
    }

    @Test
    void rejectsDirectCreate_whenBoardAlreadyHasFiftyPeople() {
        for (int index = 0; index < 50; index++) {
            roster.create(BOARD_ID, new RosterIdentity("", "", "N" + index));
        }

        assertThatThrownBy(() -> roster.create(BOARD_ID, new RosterIdentity("", "", "N50")))
                .isInstanceOf(RosterInputException.class).hasMessage("ROW_LIMIT");
        assertThat(roster.list(BOARD_ID)).hasSize(50);
        System.out.println("QA direct_create_50=accepted direct_create_51=rejected");
    }

    private static VersionedCryptoService crypto() {
        var key = new byte[32];
        Arrays.fill(key, (byte) 0x41);
        return new VersionedCryptoService(Map.of(1, key), 1);
    }

    private static TransactionOperations snapshotTransactions(InMemoryRosterRepository repository) {
        return new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                var before = repository.snapshot();
                try {
                    return action.doInTransaction(null);
                } catch (RuntimeException | Error exception) {
                    repository.restore(before);
                    throw exception;
                }
            }
        };
    }
}
