package com.naraesigning.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

final class MvpFlowFixtureEnvelopeTest {
    @Test
    void resetEmitsBoardShareEnvelopeDecryptableByFixtureCryptoContext() {
        // Given: reset writes its board row through the fixture JDBC boundary.
        var jdbc = new CapturingJdbcTemplate();

        // When: the fixture initializes its canonical board.
        MvpFlowFixture.reset(jdbc);
        var boardArguments = jdbc.boardArguments();
        assertThatCode(() -> new EncryptedValue(
                (byte[]) boardArguments[4], (byte[]) boardArguments[5], 1))
                .doesNotThrowAnyException();
        var envelope = new EncryptedValue(
                (byte[]) boardArguments[4], (byte[]) boardArguments[5], 1);

        // Then: the persisted envelope constructs and decrypts with the fixture's real crypto context.
        var crypto = new MvpFlowFixture.CryptoConfiguration().versionedCryptoService();
        assertThat(crypto.decrypt(envelope, CryptoContext.shareToken(MvpFlowFixture.BOARD, 1))).isNotEmpty();
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] boardArguments;

        @Override
        public void execute(String sql) {}

        @Override
        public int update(String sql, Object... arguments) {
            if (sql.contains("insert into board")) {
                boardArguments = arguments.clone();
            }
            return 1;
        }

        Object[] boardArguments() {
            return boardArguments;
        }
    }
}
