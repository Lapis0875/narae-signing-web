import "@testing-library/jest-dom/vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackgroundPanel } from "../background/BackgroundPanel.tsx";
import { SharePanel } from "../share/SharePanel.tsx";

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    },
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
});

describe("BackgroundPanel", () => {
  it("Given a selected file When upload is rejected Then the selection and actionable failure are retained", async () => {
    const rejected = vi.fn(async () => {
      throw new Error("rejected");
    });
    render(<BackgroundPanel disabled={false} onUpload={rejected} />);
    const input = screen.getByLabelText("PNG 또는 JPEG 파일 선택");
    const file = new File(["png"], "background.png", { type: "image/png" });

    fireEvent.change(input, {
      target: { files: { 0: file, item: () => file, length: 1 } },
    });
    fireEvent.click(screen.getByRole("button", { name: "기존 비율로 교체" }));

    await waitFor(() => expect(rejected).toHaveBeenCalledOnce());
    expect(input).toHaveProperty("files.0.name", "background.png");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "배경을 교체하지 못했습니다. 선택한 파일을 확인해 주세요.",
    );
  });

  it("Given a selected file When upload succeeds Then the selection is cleared without a failure alert", async () => {
    const accepted = vi.fn(async () => undefined);
    render(<BackgroundPanel disabled={false} onUpload={accepted} />);
    const input = screen.getByLabelText("PNG 또는 JPEG 파일 선택");
    const file = new File(["png"], "background.png", { type: "image/png" });

    fireEvent.change(input, {
      target: { files: { 0: file, item: () => file, length: 1 } },
    });
    fireEvent.click(screen.getByRole("button", { name: "기존 비율로 교체" }));

    await waitFor(() => expect(accepted).toHaveBeenCalledOnce());
    expect(screen.getByText("선택한 파일 없음")).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("SharePanel", () => {
  it("Given clipboard rejection When copy is activated Then a visible generic failure is shown", async () => {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: vi.fn(async () => {
          throw new DOMException("denied");
        }),
      },
    });
    render(
      <SharePanel
        disabled={false}
        onForceReplace={async () => undefined}
        onReissue={async () => undefined}
        share={{ shareToken: "safe-share", version: 1 }}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "링크 복사" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "링크를 복사하지 못했습니다",
    );
  });

  it("Given an active display When replacement is cancelled or confirmed Then cancel sends none and confirm sends one request without changing the share URL", async () => {
    // Given
    const replaceDisplay = vi.fn(async () => undefined);
    render(
      <SharePanel
        disabled={false}
        onForceReplace={replaceDisplay}
        onReissue={async () => undefined}
        share={{ shareToken: "safe-share", version: 1 }}
      />,
    );
    const shareQr = screen.getByRole("img", { name: "현재 서명 링크 QR" });
    const originalShareUrl = shareQr.getAttribute("data-share-url");

    // When
    fireEvent.click(screen.getByRole("button", { name: "행사장 화면 교체" }));
    expect(
      await screen.findByText(/현재 행사장 화면의 표시 연결을 종료/u),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));

    // Then
    expect(replaceDisplay).not.toHaveBeenCalled();
    expect(shareQr).toHaveAttribute("data-share-url", originalShareUrl ?? "");

    // When
    fireEvent.click(screen.getByRole("button", { name: "행사장 화면 교체" }));
    fireEvent.click(screen.getByRole("button", { name: "화면 교체" }));

    // Then
    await waitFor(() => expect(replaceDisplay).toHaveBeenCalledOnce());
    expect(shareQr).toHaveAttribute("data-share-url", originalShareUrl ?? "");
  });
});
