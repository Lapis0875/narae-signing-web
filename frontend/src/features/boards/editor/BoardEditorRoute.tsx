import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  type DragEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useReducer,
  useRef,
  useState,
} from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, describeError } from "../../../api/errors.ts";
import { AppShell } from "../../../components/AppShell.tsx";
import { ForbiddenView, LoadingView } from "../../../components/AsyncViews.tsx";
import { useToast } from "../../../components/Toast.tsx";
import { BackgroundPanel } from "../background/BackgroundPanel.tsx";
import type { Board } from "../list/boardApi.ts";
import {
  fetchRoster,
  type RosterEntry,
  rosterQueryKey,
} from "../roster/rosterApi.ts";
import { SharePanel } from "../share/SharePanel.tsx";
import { BoardToolbar } from "./BoardToolbar.tsx";
import { CanvasViewport } from "./CanvasViewport.tsx";
import {
  fetchBoardDetail,
  fetchCurrentBackground,
  fetchShare,
  forceReplaceDisplay,
  reissueShare,
  renameBoard,
  saveSlot,
  transitionBoard,
  unplaceSlot,
  uploadBackground,
} from "./editorApi.ts";
import { type Bounds, defaultPlacement } from "./geometry.ts";
import { RealtimeBoardBridge } from "./RealtimeBoardBridge.tsx";
import { RosterManagementPanel } from "./RosterManagementPanel.tsx";
import {
  type DebouncedSaveLifecycleEvent,
  SerializedSaveQueue,
} from "./saveQueue.ts";
import {
  createSaveStatusState,
  isSavePending,
  type SaveOperation,
  type SaveOwner,
  saveStatusReducer,
  selectSaveState,
} from "./saveStatus.ts";
import { useObjectUrl } from "./useObjectUrl.ts";
import "../list/boardControls.css";
import "./editor.css";

type RosterFilter = "all" | "pending" | "submitted" | "unplaced";
type BoardEditorRouteProps = { readonly actionExtensions?: ReactNode };
const colorOwner: SaveOwner = { kind: "color" };
const nonLayoutOwners: readonly SaveOwner[] = [
  { kind: "title" },
  { kind: "background" },
  { kind: "transition" },
  { kind: "share-reissue" },
  { kind: "display-replacement" },
];
const rosterFilters: readonly RosterFilter[] = [
  "all",
  "unplaced",
  "pending",
  "submitted",
];

function assertNever(value: never): never {
  throw new TypeError(`Unhandled debounce lifecycle: ${String(value)}`);
}

export function BoardEditorRoute({ actionExtensions }: BoardEditorRouteProps) {
  const { boardId = "missing" } = useParams();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const queue = useRef(new SerializedSaveQueue());
  const mounted = useRef(true);
  const boardIdentity = useRef({ boardId, generation: 1 });
  if (boardIdentity.current.boardId !== boardId)
    boardIdentity.current = {
      boardId,
      generation: boardIdentity.current.generation + 1,
    };
  const generation = boardIdentity.current.generation;
  const currentGeneration = useRef(generation);
  currentGeneration.current = generation;
  const nextAttempt = useRef(0);
  const activeColorSave = useRef<SaveOperation | null>(null);
  const debouncedLayoutOperations = useRef(new Map<string, SaveOperation>());
  const lastServerColor = useRef<Board["signatureInkColor"]>("black");
  const [saveStatus, dispatchSaveStatus] = useReducer(
    saveStatusReducer,
    generation,
    createSaveStatusState,
  );
  const [inkColor, setInkColor] = useState<Board["signatureInkColor"]>("black");
  const [filter, setFilter] = useState<RosterFilter>("all");
  const board = useQuery({
    queryFn: () => fetchBoardDetail(boardId),
    queryKey: ["admin", "boards", boardId],
  });
  const background = useQuery({
    queryFn: () => fetchCurrentBackground(boardId),
    queryKey: ["admin", "boards", boardId, "background"],
  });
  const roster = useQuery({
    queryFn: () => fetchRoster(boardId),
    queryKey: rosterQueryKey(boardId),
  });
  const share = useQuery({
    queryFn: () => fetchShare(boardId),
    queryKey: ["admin", "boards", boardId, "share"],
  });
  const backgroundUrl = useObjectUrl(background.data);
  const initialLoadError = board.error ?? roster.error;
  const colorSavePending = isSavePending(saveStatus, generation, colorOwner);
  const layoutSavePending = (roster.data ?? []).some((entry) =>
    isSavePending(saveStatus, generation, {
      kind: "layout",
      slotId: entry.slot.id,
    }),
  );
  const busy = nonLayoutOwners.some((owner) =>
    isSavePending(saveStatus, generation, owner),
  );
  const displayedSaveState = selectSaveState(saveStatus, generation);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    dispatchSaveStatus({ generation, type: "generation-changed" });
  }, [generation]);

  useEffect(() => {
    if (board.data === undefined || colorSavePending) return;
    lastServerColor.current = board.data.signatureInkColor;
    setInkColor(board.data.signatureInkColor);
  }, [board.data, colorSavePending]);

  useEffect(() => {
    if (initialLoadError !== null)
      showToast(
        `편집 화면을 불러오지 못했습니다. ${describeError(initialLoadError)}`,
      );
  }, [initialLoadError, showToast]);

  const refreshSnapshot = useCallback(async () => {
    await Promise.all([
      background.refetch(),
      board.refetch(),
      roster.refetch(),
    ]);
  }, [background.refetch, board.refetch, roster.refetch]);
  const beginSave = (owner: SaveOwner): SaveOperation => {
    const operation = {
      attempt: nextAttempt.current + 1,
      generation,
      owner,
    };
    nextAttempt.current = operation.attempt;
    dispatchSaveStatus({ ...operation, type: "operation-started" });
    return operation;
  };
  const isCurrent = (operation: SaveOperation) =>
    mounted.current && currentGeneration.current === operation.generation;
  const succeedSave = (operation: SaveOperation) => {
    dispatchSaveStatus({ ...operation, type: "operation-succeeded" });
  };
  const failSave = (operation: SaveOperation) => {
    dispatchSaveStatus({ ...operation, type: "operation-write-failed" });
  };
  const finishFailureRecovery = (operation: SaveOperation) => {
    dispatchSaveStatus({
      ...operation,
      type: "operation-recovery-finished",
    });
  };
  const cancelSave = (operation: SaveOperation) => {
    dispatchSaveStatus({ ...operation, type: "operation-cancelled" });
  };
  const reportFailure = async (operation: SaveOperation, error: unknown) => {
    failSave(operation);
    if (isCurrent(operation)) await refreshSnapshot();
    finishFailureRecovery(operation);
    if (!isCurrent(operation)) return;
    if (error instanceof ApiError) {
      showToast(
        error.status === 409
          ? "서버 배치와 충돌했습니다. 최신 상태를 불러왔습니다."
          : error.message,
      );
      return;
    }
    showToast(describeError(error));
  };
  const queueSave = (entry: RosterEntry, bounds: Bounds) => {
    const operation = beginSave({ kind: "layout", slotId: entry.slot.id });
    const handleLifecycle = (event: DebouncedSaveLifecycleEvent) => {
      const queued = debouncedLayoutOperations.current.get(entry.slot.id);
      if (
        queued?.generation === operation.generation &&
        queued.attempt === operation.attempt
      )
        debouncedLayoutOperations.current.delete(entry.slot.id);
      switch (event.type) {
        case "flushed":
          return;
        case "replaced":
          cancelSave(operation);
          return;
        default:
          return assertNever(event);
      }
    };
    const result = queue.current.enqueueDebounced(
      entry.slot.id,
      async () => {
        await saveSlot(boardId, entry.slot.id, bounds);
        if (!isCurrent(operation)) return;
        await roster.refetch();
        if (isCurrent(operation)) succeedSave(operation);
      },
      (error) => reportFailure(operation, error),
      handleLifecycle,
    );
    debouncedLayoutOperations.current.set(entry.slot.id, operation);
    void result;
  };
  const runBoardMutation = async (
    owner: SaveOwner,
    mutation: () => Promise<void>,
  ): Promise<boolean> => {
    const operation = beginSave(owner);
    try {
      await mutation();
      if (!isCurrent(operation)) return false;
      await refreshSnapshot();
      if (!isCurrent(operation)) return false;
      succeedSave(operation);
      return true;
    } catch (error) {
      failSave(operation);
      finishFailureRecovery(operation);
      if (!isCurrent(operation)) return false;
      if (error instanceof ApiError) showToast(error.message);
      else showToast(describeError(error));
      return false;
    }
  };
  const saveInkColor = async (nextColor: Board["signatureInkColor"]) => {
    if (
      activeColorSave.current?.generation === generation ||
      board.data?.status !== "설정 중" ||
      nextColor === inkColor
    )
      return;
    const previousColor = lastServerColor.current;
    const operation = beginSave(colorOwner);
    activeColorSave.current = operation;
    setInkColor(nextColor);
    try {
      const updated = await renameBoard(boardId, {
        signatureInkColor: nextColor,
      });
      if (!isCurrent(operation)) return;
      lastServerColor.current = updated.signatureInkColor;
      setInkColor(updated.signatureInkColor);
      queryClient.setQueryData(["admin", "boards", boardId], updated);
      await board.refetch();
      if (!isCurrent(operation)) return;
      lastServerColor.current = updated.signatureInkColor;
      setInkColor(updated.signatureInkColor);
      queryClient.setQueryData<Board>(
        ["admin", "boards", boardId],
        (current) =>
          current === undefined
            ? updated
            : { ...current, signatureInkColor: updated.signatureInkColor },
      );
      succeedSave(operation);
    } catch (error) {
      if (!isCurrent(operation)) return;
      if (error instanceof ApiError && error.status === 409) {
        failSave(operation);
        const latest = await board.refetch();
        if (!isCurrent(operation)) return;
        if (latest.data !== undefined) {
          lastServerColor.current = latest.data.signatureInkColor;
          setInkColor(latest.data.signatureInkColor);
        }
        finishFailureRecovery(operation);
        showToast(
          "다른 화면에서 서명이 시작되었습니다. 최신 상태를 불러왔습니다.",
        );
      } else {
        setInkColor(previousColor);
        failSave(operation);
        finishFailureRecovery(operation);
        showToast(
          error instanceof ApiError ? error.message : describeError(error),
        );
      }
    } finally {
      if (
        activeColorSave.current?.generation === operation.generation &&
        activeColorSave.current.attempt === operation.attempt
      )
        activeColorSave.current = null;
    }
  };
  const runTransition = async (action: "open" | "close" | "reopen") => {
    if (
      activeColorSave.current?.generation === generation ||
      queue.current.isBusy()
    )
      return;
    const succeeded = await runBoardMutation(
      { kind: "transition" },
      async () => {
        await transitionBoard(boardId, action);
      },
    );
    if (succeeded) showToast("서명 상태를 변경했습니다.");
  };
  const replaceBackground = async (file: File, adoptSourceRatio: boolean) => {
    const operation = beginSave({ kind: "background" });
    try {
      await uploadBackground(boardId, file, adoptSourceRatio, adoptSourceRatio);
      if (!isCurrent(operation)) return;
      await refreshSnapshot();
      if (!isCurrent(operation)) return;
      succeedSave(operation);
      showToast("배경을 교체했습니다.");
    } catch (error) {
      failSave(operation);
      finishFailureRecovery(operation);
      if (!isCurrent(operation)) return;
      if (error instanceof ApiError) showToast(error.message);
      else showToast(describeError(error));
      throw error;
    }
  };
  const filteredEntries = (roster.data ?? []).filter((entry) => {
    switch (filter) {
      case "all":
        return true;
      case "pending":
        return !entry.submitted && entry.slot.placementStatus === "PLACED";
      case "submitted":
        return entry.submitted;
      case "unplaced":
        return entry.slot.placementStatus === "UNPLACED";
    }
    return false;
  });

  if (board.isPending || roster.isPending || share.isPending)
    return <LoadingView />;
  if (initialLoadError instanceof ApiError && initialLoadError.status === 403)
    return <ForbiddenView />;
  if (board.data === undefined || roster.data === undefined) {
    return (
      <AppShell>
        <section className="app-panel">
          <p role="alert">편집 화면을 불러오지 못했습니다.</p>
        </section>
      </AppShell>
    );
  }

  return (
    <AppShell mainClassName="app-main--editor">
      <RealtimeBoardBridge
        boardId={boardId}
        refetchSnapshot={refreshSnapshot}
      />
      <main className="board-editor board-workflow">
        <h1 className="editor-route-title">{board.data.title}</h1>
        <BoardToolbar
          actionExtensions={
            board.data.status === "서명 진행" ? null : actionExtensions
          }
          board={board.data}
          disabled={busy}
          inkColor={inkColor}
          inkColorHelp={
            board.data.status !== "설정 중"
              ? "서명이 시작된 뒤에는 색상을 변경할 수 없습니다."
              : background.isPending ||
                  background.isError ||
                  background.data === null
                ? "배경 이미지를 먼저 등록해 주세요"
                : "흰색 서명은 밝은 배경에서 잘 보이지 않을 수 있습니다. 기존 미리보기는 유지됩니다."
          }
          inkColorLocked={
            busy || colorSavePending || board.data.status !== "설정 중"
          }
          onInkColorChange={(color) => {
            void saveInkColor(color);
          }}
          onRename={async (title) => {
            await runBoardMutation({ kind: "title" }, async () => {
              await renameBoard(boardId, { title });
            });
          }}
          onTransition={runTransition}
          saveState={displayedSaveState}
          transitionDisabled={busy || colorSavePending || layoutSavePending}
          whiteDisabled={
            background.isPending ||
            background.isError ||
            background.data === null
          }
        />
        <nav className="editor-links" aria-label="보드 편집 이동">
          <Link className="board-button" to="/boards">
            보드 목록
          </Link>
          <Link
            className="board-button board-button--primary"
            rel="noopener noreferrer"
            target="_blank"
            to={`/boards/${boardId}/full`}
          >
            전체보기
          </Link>
        </nav>
        <div className="editor-layout">
          <div className="editor-main-column">
            <section className="editor-canvas-panel">
              {background.isPending || background.isError ? (
                <div
                  aria-busy={background.isPending}
                  className="editor-canvas editor-background-state"
                  role={background.isError ? "alert" : "status"}
                  style={{
                    aspectRatio: `${board.data.canvasWidth} / ${board.data.canvasHeight}`,
                  }}
                >
                  <p>
                    {background.isError
                      ? "배경을 불러오지 못했습니다. 다시 시도해 주세요."
                      : "배경을 불러오는 중입니다."}
                  </p>
                  {background.isError ? (
                    <button
                      className="board-button board-button--primary"
                      disabled={background.isFetching}
                      onClick={() => void background.refetch()}
                      type="button"
                    >
                      배경 다시 불러오기
                    </button>
                  ) : null}
                </div>
              ) : (
                <CanvasViewport
                  backgroundUrl={backgroundUrl}
                  canvasHeight={board.data.canvasHeight}
                  canvasWidth={board.data.canvasWidth}
                  entries={roster.data}
                  onSave={queueSave}
                  onUnplace={(entry) => {
                    const operation = beginSave({
                      kind: "layout",
                      slotId: entry.slot.id,
                    });
                    void queue.current.enqueue(
                      async () => {
                        await unplaceSlot(boardId, entry.slot.id);
                        if (!isCurrent(operation)) return;
                        await roster.refetch();
                        if (isCurrent(operation)) succeedSave(operation);
                      },
                      (error) => reportFailure(operation, error),
                    );
                  }}
                />
              )}
            </section>
            <section
              className="editor-panel editor-operations"
              aria-labelledby="operations-title"
            >
              <h2 id="operations-title">운영 현황</h2>
              <fieldset
                className="editor-actions editor-filter"
                aria-label="명단 상태 필터"
              >
                {rosterFilters.map((value) => (
                  <button
                    aria-pressed={filter === value}
                    className="board-button"
                    key={value}
                    onClick={() => setFilter(value)}
                    type="button"
                  >
                    {
                      {
                        all: "전체",
                        pending: "미제출",
                        submitted: "제출",
                        unplaced: "미배치",
                      }[value]
                    }
                  </button>
                ))}
              </fieldset>
              <ul className="editor-roster-list">
                {filteredEntries.map((entry) => (
                  <li key={entry.id}>
                    {entry.identity.name} ·{" "}
                    {entry.slot.placementStatus === "UNPLACED"
                      ? "미배치"
                      : entry.submitted
                        ? "제출"
                        : "미제출"}
                  </li>
                ))}
              </ul>
            </section>
            <RosterManagementPanel boardId={boardId} entries={roster.data} />
          </div>
          <aside className="editor-sidebar">
            <section className="editor-panel" aria-labelledby="unplaced-title">
              <h2 id="unplaced-title">미배치 명단</h2>
              <ul className="editor-roster-list">
                {roster.data
                  .filter((entry) => entry.slot.placementStatus === "UNPLACED")
                  .map((entry) => (
                    <li key={entry.id}>
                      <button
                        aria-label={`${entry.identity.name} 배치`}
                        className="board-button"
                        draggable
                        onClick={() =>
                          queueSave(
                            entry,
                            defaultPlacement(
                              roster.data.filter(
                                (candidate) =>
                                  candidate.slot.placementStatus === "PLACED",
                              ).length,
                            ),
                          )
                        }
                        onDragStart={(event: DragEvent<HTMLButtonElement>) =>
                          event.dataTransfer.setData(
                            "text/slot-id",
                            entry.slot.id,
                          )
                        }
                        type="button"
                      >
                        {entry.identity.name}
                      </button>
                    </li>
                  ))}
              </ul>
            </section>
            <BackgroundPanel
              disabled={busy || background.isError}
              onUpload={replaceBackground}
            />
            {share.data === undefined ? (
              <section className="editor-panel">
                <h2>공유</h2>
                <p>공유 링크를 불러오지 못했습니다.</p>
              </section>
            ) : (
              <SharePanel
                disabled={busy}
                onForceReplace={async () => {
                  const succeeded = await runBoardMutation(
                    { kind: "display-replacement" },
                    async () => {
                      await forceReplaceDisplay(boardId);
                    },
                  );
                  if (succeeded) showToast("행사장 화면을 교체했습니다.");
                }}
                onReissue={async () => {
                  const succeeded = await runBoardMutation(
                    { kind: "share-reissue" },
                    async () => {
                      await reissueShare(boardId);
                      await queryClient.invalidateQueries({
                        queryKey: ["admin", "boards", boardId, "share"],
                      });
                    },
                  );
                  if (succeeded) showToast("공유 링크를 재발급했습니다.");
                }}
                share={share.data}
              />
            )}
          </aside>
        </div>
      </main>
    </AppShell>
  );
}
