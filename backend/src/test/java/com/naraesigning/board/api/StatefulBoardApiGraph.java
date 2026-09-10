package com.naraesigning.board.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.background.BackgroundAssetView;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.BoardShare;
import com.naraesigning.board.core.BoardView;
import com.naraesigning.board.core.CreatedBoard;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.roster.RosterEntry;
import com.naraesigning.roster.RosterIdentity;
import com.naraesigning.roster.RosterService;
import com.naraesigning.realtime.BoardMutationEvent;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.slot.Slot;
import com.naraesigning.slot.SlotBounds;
import com.naraesigning.slot.SlotService;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class StatefulBoardApiGraph {
    private static final UUID BACKGROUND_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final List<String> transactionTrace = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final BoardCoreHarness boardCore = new BoardCoreHarness(transactions());
    private final RosterHarness roster = new RosterHarness();
    private final Map<UUID, SlotState> slots = new LinkedHashMap<>();
    private final SlotService slotService = mock(SlotService.class, this::slotCall);
    private final BackgroundAssetService backgrounds = mock(BackgroundAssetService.class, this::backgroundCall);
    private final JdbcOperations jdbc = mock(JdbcOperations.class, this::jdbcCall);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class, this::eventCall);
    private final BoardAdminFacade facade = new AuditedFacade(new JdbcBoardAdminFacade(
            boardCore.service, slotService, backgrounds, jdbc, transactions(), publisher,
            mock(LiveSignatureRegistry.class)));
    private int ownerLookups;
    private boolean failPostTransitionDetail;
    private BackgroundState background;

    BoardAdminFacade facade() { return facade; }
    BoardService boardService() { return boardCore.service; }
    Object rosterController() { return roster.controller; }
    Object rosterAdvice() { return roster.advice; }
    UUID boardId() { return boardCore.boardId(); }

    void markSubmitted(UUID entryId) {
        roster.invokeRepository("markSubmitted", entryId);
        var entry = roster.service.list(boardId()).stream().filter(value -> value.id().equals(entryId))
                .findFirst().orElseThrow();
        slot(entry).signatureDigest = digest("synthetic-signature:" + entry.slot().id());
    }

    void failNextPostTransitionDetail() { failPostTransitionDetail = true; }

    String canonicalGraph() {
        if (!boardCore.hasBoard()) return "boards=[];background=null;roster=[];slots=[];events=[]";
        syncSlots();
        var entries = roster.service.list(boardId()).stream()
                .sorted(Comparator.comparing(entry -> entry.id().toString()))
                .map(this::canonicalRosterEntry).toList();
        var slotStates = slots.values().stream()
                .sorted(Comparator.comparing(slot -> slot.id.toString()))
                .map(SlotState::canonical).toList();
        return "board={" + boardCore.canonicalBoard() + "};background="
                + (background == null ? "null" : background.canonical())
                + ";roster=" + entries + ";slots=" + slotStates + ";events=" + events;
    }

    String canonicalDigest() { return digest(canonicalGraph()); }
    static String digestCanonical(String canonical) { return digest(canonical); }
    List<String> transactionTrace() { return List.copyOf(transactionTrace); }
    String securityAudit() { return "ownerLookups=" + ownerLookups; }

    private String canonicalRosterEntry(RosterEntry entry) {
        var storedSlot = entry.slot();
        return "entry={id=" + entry.id() + ",identityDigest=" + identityDigest(entry.identity())
                + ",submitted=" + entry.submitted() + ",storedSlot={id=" + storedSlot.id()
                + ",placement=" + storedSlot.placementStatus() + ",x=" + value(storedSlot.x())
                + ",y=" + value(storedSlot.y()) + ",width=" + value(storedSlot.width())
                + ",height=" + value(storedSlot.height()) + ",revision=" + storedSlot.revision() + "}}";
    }

    private Object slotCall(InvocationOnMock invocation) {
        UUID targetBoard = invocation.getArgument(0);
        UUID slotId = invocation.getArgument(1);
        boardCore.requireBoard(targetBoard);
        var entry = entryForSlot(slotId);
        var state = slot(entry);
        return switch (invocation.getMethod().getName()) {
            case "updateVisual" -> {
                var bounds = (SlotBounds) invocation.getArgument(2);
                roster.invokeRepository("place", entry.id());
                if (state.bounds == null) state.revision++;
                state.bounds = bounds;
                yield state.view(targetBoard, entry.id(), entry.submitted());
            }
            case "delete" -> {
                state.bounds = null;
                state.signatureDigest = null;
                state.revision++;
                roster.invokeRepository("resetSubmitted", entry.id());
                yield state.view(targetBoard, entry.id(), false);
            }
            case "resetSignature" -> {
                state.signatureDigest = null;
                state.revision++;
                roster.invokeRepository("resetSubmitted", entry.id());
                yield state.view(targetBoard, entry.id(), false);
            }
            default -> throw new UnsupportedOperationException(invocation.getMethod().getName());
        };
    }

    private Object backgroundCall(InvocationOnMock invocation) {
        boardCore.requireBoard(invocation.getArgument(0));
        if ("current".equals(invocation.getMethod().getName())) {
            return java.util.Optional.ofNullable(background).map(value -> mock(
                    com.naraesigning.background.BackgroundContent.class));
        }
        var bytes = (byte[]) invocation.getArgument(1);
        background = new BackgroundState(BACKGROUND_ID, 1920, 1080, "image/png", digest(bytes));
        return new BackgroundAssetView(BACKGROUND_ID, 1920, 1080, "image/png");
    }

    private Object jdbcCall(InvocationOnMock invocation) throws Exception {
        return switch (invocation.getMethod().getName()) {
            case "query" -> extractStatus(invocation);
            case "queryForMap" -> openCounts();
            case "update" -> transition(invocation);
            default -> throw new UnsupportedOperationException(invocation.getMethod().getName());
        };
    }

    private Object extractStatus(InvocationOnMock invocation) throws Exception {
        @SuppressWarnings("unchecked")
        var extractor = (ResultSetExtractor<Object>) invocation.getArgument(1);
        var resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(boardCore.status());
        return extractor.extractData(resultSet);
    }

    private Map<String, Object> openCounts() {
        syncSlots();
        long unplaced = slots.values().stream().filter(slot -> slot.bounds == null).count();
        return Map.of("roster_count", (long) slots.size(), "unplaced_count", unplaced);
    }

    private int transition(InvocationOnMock invocation) {
        String changed = invocation.getArgument(1);
        UUID targetBoard = invocation.getArgument(2);
        String expected = invocation.getArgument(3);
        boardCore.requireBoard(targetBoard);
        if (!boardCore.status().equals(expected)) return 0;
        boardCore.setStatus(targetBoard, failPostTransitionDetail ? "DELETING" : changed);
        transactionTrace.add("db:" + changed);
        if ("OPEN".equals(changed)) roster.invokeRepository("open");
        return 1;
    }

    private Object eventCall(InvocationOnMock invocation) {
        if (transactionTrace.isEmpty() || !"commit".equals(transactionTrace.getLast())) {
            throw new AssertionError("event published before commit");
        }
        Object event = invocation.getArgument(0);
        String type = event instanceof BoardLifecycleEvent lifecycle ? lifecycle.status()
                : ((BoardMutationEvent) event).type();
        events.add(type);
        transactionTrace.add("event:" + type);
        return null;
    }

    private TransactionOperations transactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                String statusBefore = boardCore.hasBoard() ? boardCore.status() : null;
                int traceSize = transactionTrace.size();
                int eventSize = events.size();
                TransactionSynchronizationManager.initSynchronization();
                try {
                    T result = action.doInTransaction(mock(TransactionStatus.class));
                    var synchronizations = TransactionSynchronizationManager.getSynchronizations();
                    TransactionSynchronizationManager.clearSynchronization();
                    transactionTrace.add("commit");
                    synchronizations.forEach(synchronization -> synchronization.afterCommit());
                    return result;
                } catch (RuntimeException exception) {
                    TransactionSynchronizationManager.clearSynchronization();
                    if (statusBefore != null) boardCore.setStatus(boardCore.boardId(), statusBefore);
                    failPostTransitionDetail = false;
                    while (transactionTrace.size() > traceSize) transactionTrace.removeLast();
                    while (events.size() > eventSize) events.removeLast();
                    transactionTrace.add("rollback");
                    throw exception;
                }
            }

            @Override public void executeWithoutResult(java.util.function.Consumer<TransactionStatus> action) {
                action.accept(mock(TransactionStatus.class));
            }
        };
    }

    private void syncSlots() {
        roster.service.list(boardId()).forEach(this::slot);
        var retained = roster.service.list(boardId()).stream().map(RosterEntry::slot)
                .map(RosterEntry.Slot::id).toList();
        slots.keySet().removeIf(slotId -> !retained.contains(slotId));
    }

    private SlotState slot(RosterEntry entry) {
        return slots.computeIfAbsent(entry.slot().id(), ignored -> SlotState.from(entry));
    }

    private RosterEntry entryForSlot(UUID slotId) {
        return roster.service.list(boardId()).stream().filter(entry -> entry.slot().id().equals(slotId))
                .findFirst().orElseThrow(() -> new IllegalStateException("slot not in graph"));
    }

    private static String identityDigest(RosterIdentity identity) {
        var digest = sha256();
        add(digest, identity.organization());
        add(digest, identity.job());
        add(digest, identity.name());
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String value(java.math.BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] value) {
        return java.util.HexFormat.of().formatHex(sha256().digest(value));
    }
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private final class AuditedFacade implements BoardAdminFacade {
        private final BoardAdminFacade delegate;
        private AuditedFacade(BoardAdminFacade delegate) { this.delegate = delegate; }
        @Override public void authorize(BoardOwner owner, UUID boardId) {
            ownerLookups++;
            delegate.authorize(owner, boardId);
        }
        @Override public List<BoardView> list(BoardOwner owner) { return delegate.list(owner); }
        @Override public CreatedBoard create(BoardOwner owner, String title) { return delegate.create(owner, title); }
        @Override public BoardView detail(BoardOwner owner, UUID boardId) { return delegate.detail(owner, boardId); }
        @Override public BoardView patch(BoardOwner owner, UUID boardId, BoardPatch patch) {
            return delegate.patch(owner, boardId, patch);
        }
        @Override public Slot updateSlot(BoardOwner owner, UUID boardId, UUID slotId, SlotBounds bounds) {
            return delegate.updateSlot(owner, boardId, slotId, bounds);
        }
        @Override public Slot deleteSlot(BoardOwner owner, UUID boardId, UUID slotId) {
            return delegate.deleteSlot(owner, boardId, slotId);
        }
        @Override public Slot resetSignature(BoardOwner owner, UUID boardId, UUID slotId) {
            return delegate.resetSignature(owner, boardId, slotId);
        }
        @Override public BoardShare share(BoardOwner owner, UUID boardId) { return delegate.share(owner, boardId); }
        @Override public BoardShare reissueShare(BoardOwner owner, UUID boardId) {
            return delegate.reissueShare(owner, boardId);
        }
        @Override public BackgroundAssetView replaceBackground(BoardOwner owner, UUID boardId, byte[] bytes,
                String mimeType, com.naraesigning.background.CanvasChange change) {
            return delegate.replaceBackground(owner, boardId, bytes, mimeType, change);
        }
        @Override public BoardView open(BoardOwner owner, UUID boardId) { return delegate.open(owner, boardId); }
        @Override public BoardView close(BoardOwner owner, UUID boardId) { return delegate.close(owner, boardId); }
        @Override public BoardView reopen(BoardOwner owner, UUID boardId) { return delegate.reopen(owner, boardId); }
    }

    private static final class SlotState {
        private final UUID id;
        private SlotBounds bounds;
        private long revision;
        private String signatureDigest;

        private SlotState(UUID id, SlotBounds bounds, long revision) {
            this.id = id;
            this.bounds = bounds;
            this.revision = revision;
        }

        private static SlotState from(RosterEntry entry) {
            var slot = entry.slot();
            var bounds = "PLACED".equals(slot.placementStatus())
                    ? SlotBounds.of(slot.x(), slot.y(), slot.width(), slot.height()) : null;
            return new SlotState(slot.id(), bounds, slot.revision());
        }

        private Slot view(UUID boardId, UUID entryId, boolean submitted) {
            return new Slot(boardId, id, entryId, bounds, revision,
                    submitted, signatureDigest != null);
        }

        private String canonical() {
            return "slot={id=" + id + ",placed=" + (bounds != null) + ",x="
                    + (bounds == null ? "null" : value(bounds.x())) + ",y="
                    + (bounds == null ? "null" : value(bounds.y())) + ",width="
                    + (bounds == null ? "null" : value(bounds.width())) + ",height="
                    + (bounds == null ? "null" : value(bounds.height())) + ",revision=" + revision
                    + ",signaturePresent=" + (signatureDigest != null)
                    + ",signatureDigest=" + signatureDigest + "}";
        }
    }

    private record BackgroundState(UUID id, int width, int height, String mimeType, String contentDigest) {
        private String canonical() {
            return "{id=" + id + ",width=" + width + ",height=" + height + ",mimeType="
                    + mimeType + ",contentDigest=" + contentDigest + "}";
        }
    }

    private static final class BoardCoreHarness {
        private final Object repository;
        private final BoardService service;

        private BoardCoreHarness(TransactionOperations transactions) {
            try {
                var repositoryType = Class.forName("com.naraesigning.board.core.BoardRepository");
                var repositoryClass = Class.forName("com.naraesigning.board.core.InMemoryBoardRepository");
                repository = accessible(repositoryClass.getDeclaredConstructor()).newInstance();
                var key = new byte[32];
                java.util.Arrays.fill(key, (byte) 0x5a);
                var constructor = BoardService.class.getDeclaredConstructor(
                        repositoryType, VersionedCryptoService.class, TransactionOperations.class);
                service = (BoardService) accessible(constructor).newInstance(repository,
                        new VersionedCryptoService(Map.of(1, key), 1), transactions);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private boolean hasBoard() { return !records().isEmpty(); }
        private UUID boardId() { return records().keySet().stream().findFirst().orElseThrow(); }
        private void requireBoard(UUID boardId) {
            if (!records().containsKey(boardId)) throw new com.naraesigning.board.core.BoardUnavailableException();
        }
        private String status() { return invoke(records().get(boardId()), "status").toString(); }

        private void setStatus(UUID boardId, String status) {
            try {
                var stored = records().get(boardId);
                var statusType = Class.forName("com.naraesigning.board.core.BoardStatus");
                @SuppressWarnings({"rawtypes", "unchecked"})
                var changedStatus = Enum.valueOf((Class<? extends Enum>) statusType, status);
                var withStatus = stored.getClass().getDeclaredMethod("withStatus", statusType);
                withStatus.setAccessible(true);
                records().put(boardId, withStatus.invoke(stored, changedStatus));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String canonicalBoard() {
            var stored = records().get(boardId());
            var owner = invoke(stored, "owner");
            var title = invoke(invoke(stored, "title"), "value").toString();
            var share = invoke(stored, "share");
            var encrypted = invoke(share, "encryptedToken");
            return "id=" + boardId() + ",ownerDigest=" + digest(invoke(owner, "id").toString())
                    + ",titleDigest=" + digest(title) + ",titleCodePoints="
                    + title.codePointCount(0, title.length()) + ",status=" + status()
                    + ",canvasWidth=" + invoke(stored, "canvasWidth")
                    + ",canvasHeight=" + invoke(stored, "canvasHeight")
                    + ",createdAt=" + invoke(stored, "createdAt") + ",updatedAt=" + invoke(stored, "updatedAt")
                    + ",shareSet=[{version=" + invoke(share, "version")
                    + ",lookupHash=" + digest((byte[]) invoke(share, "lookupHash"))
                    + ",ciphertextDigest=" + digest((byte[]) invoke(encrypted, "ciphertext"))
                    + ",nonceDigest=" + digest((byte[]) invoke(encrypted, "nonce"))
                    + ",keyVersion=" + invoke(encrypted, "keyVersion") + "}]";
        }

        @SuppressWarnings("unchecked")
        private Map<UUID, Object> records() {
            try {
                var field = repository.getClass().getDeclaredField("records");
                field.setAccessible(true);
                return (Map<UUID, Object>) field.get(repository);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class RosterHarness {
        private final Object repository;
        private final RosterService service;
        private final Object controller;
        private final Object advice;

        private RosterHarness() {
            try {
                var repositoryType = Class.forName("com.naraesigning.roster.RosterRepository");
                var repositoryClass = Class.forName("com.naraesigning.roster.InMemoryRosterRepository");
                repository = accessible(repositoryClass.getDeclaredConstructor()).newInstance();
                var key = new byte[32];
                java.util.Arrays.fill(key, (byte) 0x33);
                var constructor = RosterService.class.getDeclaredConstructor(
                        repositoryType, VersionedCryptoService.class, TransactionOperations.class);
                service = (RosterService) accessible(constructor).newInstance(repository,
                        new VersionedCryptoService(Map.of(1, key), 1), directTransactions());
                controller = controller(service);
                advice = accessible(Class.forName("com.naraesigning.roster.RosterApiAdvice")
                        .getDeclaredConstructor()).newInstance();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private void invokeRepository(String method, Object... arguments) {
            try {
                var types = java.util.Arrays.stream(arguments).map(Object::getClass).toArray(Class<?>[]::new);
                var target = repository.getClass().getDeclaredMethod(method, types);
                target.setAccessible(true);
                target.invoke(repository, arguments);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(exception.getCause());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static Object controller(RosterService service) throws ReflectiveOperationException {
            var controller = Class.forName("com.naraesigning.roster.RosterController");
            var json = Class.forName("com.naraesigning.roster.RosterJsonParser");
            var csv = Class.forName("com.naraesigning.roster.RosterCsvParser");
            var xlsx = Class.forName("com.naraesigning.roster.RosterXlsxParser");
            var jsonParser = accessible(json.getDeclaredConstructor(ObjectMapper.class)).newInstance(new ObjectMapper());
            var csvParser = accessible(csv.getDeclaredConstructor()).newInstance();
            var xlsxParser = accessible(xlsx.getDeclaredConstructor()).newInstance();
            Constructor<?> constructor = controller.getDeclaredConstructor(RosterService.class, json, csv, xlsx);
            return accessible(constructor).newInstance(service, jsonParser, csvParser, xlsxParser);
        }
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
            @Override public void executeWithoutResult(java.util.function.Consumer<TransactionStatus> action) {
                action.accept(mock(TransactionStatus.class));
            }
        };
    }

    private static Object invoke(Object target, String method) {
        try {
            var declared = target.getClass().getDeclaredMethod(method);
            declared.setAccessible(true);
            return declared.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T value) {
        value.setAccessible(true);
        return value;
    }
}
