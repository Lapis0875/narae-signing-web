import { useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { z } from "zod";
import { apiRequest } from "../../../api/client.ts";
import { ConfirmDialog } from "../../../components/ConfirmDialog.tsx";

type DeleteBoardActionProps = {
  readonly boardId?: string;
  readonly onDeleted?: () => Promise<unknown>;
};

export function DeleteBoardAction({ boardId: providedBoardId, onDeleted }: DeleteBoardActionProps) {
  const { boardId: routeBoardId = "missing" } = useParams();
  const boardId = providedBoardId ?? routeBoardId;
  const navigate = useNavigate();
  const invokerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const confirm = async () => {
    setOpen(false);
    setDeleting(true);
    try {
      await apiRequest(`/api/v1/admin/boards/${z.uuid().parse(boardId)}`, {
        body: JSON.stringify({ confirmed: true }),
        headers: { "Content-Type": "application/json" },
        method: "DELETE",
      });
      if (onDeleted === undefined) {
        navigate("/boards", { replace: true });
      } else {
        await onDeleted();
      }
    } finally {
      setDeleting(false);
    }
  };

  return (
    <>
      <button
        className="board-button board-button--destructive"
        disabled={deleting}
        onClick={() => setOpen(true)}
        ref={invokerRef}
        type="button"
      >
        보드 영구 삭제
      </button>
      <ConfirmDialog
        confirmLabel="영구 삭제"
        invokerRef={invokerRef}
        message="보드와 모든 서명을 영구 삭제합니다. 이 작업은 되돌릴 수 없습니다."
        onCancel={() => setOpen(false)}
        onConfirm={() => void confirm()}
        open={open}
      />
    </>
  );
}
