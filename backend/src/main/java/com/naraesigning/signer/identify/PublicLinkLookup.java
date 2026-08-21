package com.naraesigning.signer.identify;

import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.PublicBoardLink;
import java.util.Optional;
import java.util.function.Function;

final class PublicLinkLookup {
    private final Function<String, Optional<PublicBoardLink>> canonical;
    private final PublicIdentifyRepository repository;

    PublicLinkLookup(BoardService boards, PublicIdentifyRepository repository) {
        this(boards::findPublic, repository);
    }

    PublicLinkLookup(
            Function<String, Optional<PublicBoardLink>> canonical,
            PublicIdentifyRepository repository) {
        this.canonical = canonical;
        this.repository = repository;
    }

    Optional<LinkRecord> resolve(String rawToken) {
        return canonical.apply(rawToken).flatMap(link ->
                repository.findLink(link.boardId(), link.shareLinkVersion()));
    }

    Optional<SignerRecord> findSigner(LinkRecord link, byte[] identityHmac) {
        return repository.findSigner(link.boardId(), link.linkVersion(), identityHmac);
    }
}
