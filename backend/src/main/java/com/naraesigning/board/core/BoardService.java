package com.naraesigning.board.core;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public final class BoardService {
    private static final int DEFAULT_CANVAS_WIDTH = 1920;
    private static final int DEFAULT_CANVAS_HEIGHT = 1080;

    private final BoardRepository repository;
    private final VersionedCryptoService crypto;
    private final TransactionOperations transactions;

    BoardService(
            BoardRepository repository,
            VersionedCryptoService crypto,
            TransactionOperations transactions) {
        this.repository = repository;
        this.crypto = crypto;
        this.transactions = transactions;
    }

    public CreatedBoard create(BoardOwner owner, String rawTitle) {
        var title = new BoardTitle(rawTitle);
        var boardId = UUID.randomUUID();
        var issued = BoardShareToken.issue(boardId, 1, crypto);
        var stored = repository.create(new NewBoard(
                boardId,
                owner,
                title,
                BoardStatus.DRAFT,
                DEFAULT_CANVAS_WIDTH,
                DEFAULT_CANVAS_HEIGHT,
                SignatureInkColor.BLACK,
                issued.stored()));
        return new CreatedBoard(BoardView.from(stored), issued.rawToken());
    }

    public List<BoardView> list(BoardOwner owner) {
        return repository.list(owner).stream().map(BoardView::from).toList();
    }

    public BoardView detail(BoardOwner owner, UUID boardId) {
        return BoardView.from(repository.find(owner, boardId).orElseThrow(BoardUnavailableException::new));
    }

    public BoardView rename(BoardOwner owner, UUID boardId, String rawTitle) {
        return BoardView.from(repository.rename(owner, boardId, new BoardTitle(rawTitle))
                .orElseThrow(BoardUnavailableException::new));
    }

    public BoardView lock(BoardOwner owner, UUID boardId) {
        return BoardView.from(repository.lock(owner, boardId).orElseThrow(BoardUnavailableException::new));
    }

    public BoardView patchLocked(BoardOwner owner, UUID boardId, String rawTitle, boolean titlePresent,
            SignatureInkColor signatureInkColor, boolean signatureInkColorPresent) {
        var current = repository.find(owner, boardId).orElseThrow(BoardUnavailableException::new);
        var title = titlePresent ? new BoardTitle(rawTitle) : current.title();
        var color = signatureInkColorPresent ? signatureInkColor : current.signatureInkColor();
        return BoardView.from(repository.patch(owner, boardId, title, color)
                .orElseThrow(BoardUnavailableException::new));
    }

    public String currentShare(BoardOwner owner, UUID boardId) {
        var board = repository.find(owner, boardId).orElseThrow(BoardUnavailableException::new);
        return decrypt(board.id(), board.share());
    }

    public BoardShare reissueShare(BoardOwner owner, UUID boardId) {
        return reissueShare(owner, boardId, () -> {});
    }

    public BoardShare reissueShare(BoardOwner owner, UUID boardId, Runnable afterReplaced) {
        return transactions.execute(status -> {
            var current = repository.lockShare(owner, boardId)
                    .orElseThrow(BoardUnavailableException::new);
            if (current.share().version() == Integer.MAX_VALUE) {
                throw new BoardUnavailableException();
            }
            int nextVersion = current.share().version() + 1;
            var issued = BoardShareToken.issue(boardId, nextVersion, crypto);
            repository.replaceShare(owner, boardId, current.share().version(), issued.stored())
                    .orElseThrow(BoardUnavailableException::new);
            afterReplaced.run();
            return new BoardShare(nextVersion, issued.rawToken());
        });
    }

    public Optional<PublicBoardLink> findPublic(String rawToken) {
        return BoardShareToken.parseLookupHash(rawToken)
                .flatMap(repository::findPublicByLookupHash)
                .map(board -> new PublicBoardLink(
                        board.boardId(),
                        board.title(),
                        BoardView.statusLabel(board.status()),
                        board.shareLinkVersion()));
    }

    private String decrypt(UUID boardId, StoredShare share) {
        var plaintext = crypto.decrypt(
                share.encryptedToken(), CryptoContext.shareToken(boardId, share.version()));
        try {
            return new String(plaintext, StandardCharsets.US_ASCII);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }
}
