import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { z } from "zod";
import { apiRequest } from "../../../api/client.ts";
import { ConfirmDialog } from "../../../components/ConfirmDialog.tsx";

export function DeleteBoardAction() {
  const { boardId = "missing" } = useParams();
  const navigate = useNavigate();
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
      navigate("/boards", { replace: true });
    } finally {
      setDeleting(false);
    }
  };

  return (
    <>
      <button disabled={deleting} onClick={() => setOpen(true)} type="button">
        보드 영구 삭제
      </button>
      <ConfirmDialog
        confirmLabel="영구 삭제"
        message="보드와 모든 서명을 영구 삭제합니다. 이 작업은 되돌릴 수 없습니다."
        onCancel={() => setOpen(false)}
        onConfirm={() => void confirm()}
        open={open}
      />
    </>
  );
}
