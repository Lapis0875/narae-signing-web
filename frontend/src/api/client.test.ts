// @vitest-environment-options { "url": "http://localhost/" }
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest, setCsrfToken } from "./client.ts";
import { ApiError } from "./errors.ts";

const mutationPaths = [
  "/api/v1/auth/login",
  "/api/v1/admin/boards/board-1/roster/entry-1",
  "/api/v1/public/links/share-token/identify",
  "/api/v1/public/signing-session/signature",
] as const;

function setDocumentCookie(cookie: string): void {
  Reflect.set(document, "cookie", cookie);
}

describe("apiRequest CSRF boundary", () => {
  beforeEach(() => {
    setDocumentCookie("XSRF-TOKEN=; Max-Age=0; Path=/");
    setCsrfToken(null);
    vi.restoreAllMocks();
  });

  it.each(mutationPaths)(
    "sends cookie CSRF and credentials for %s",
    async (path) => {
      // Given
      setDocumentCookie("XSRF-TOKEN=sanitized-token; Path=/");
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(new Response(null, { status: 204 }));

      // When
      await apiRequest(path, { method: "POST" });

      // Then
      const requestInit = fetchSpy.mock.lastCall?.[1];
      expect(fetchSpy).toHaveBeenCalledOnce();
      expect(requestInit?.credentials).toBe("same-origin");
      expect(new Headers(requestInit?.headers).get("X-XSRF-TOKEN")).toBe(
        "sanitized-token",
      );
      expect(new Headers(requestInit?.headers).get("X-CSRF-TOKEN")).toBeNull();
    },
  );

  it("keeps GET and SSE reads free of CSRF headers", async () => {
    // Given
    setDocumentCookie("XSRF-TOKEN=sanitized-token; Path=/");
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }));

    // When
    await apiRequest("/api/v1/admin/boards/board-1/events");

    // Then
    const requestInit = fetchSpy.mock.lastCall?.[1];
    expect(new Headers(requestInit?.headers).get("X-XSRF-TOKEN")).toBeNull();
    expect(new Headers(requestInit?.headers).get("X-CSRF-TOKEN")).toBeNull();
  });

  it.each(["/api/v1/auth/csrf", "/api/v1/public/links/share-token"])(
    "captures the CSRF cookie materialized by GET %s",
    async (path) => {
      // Given
      vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
        setDocumentCookie("XSRF-TOKEN=materialized-token; Path=/");
        return new Response(null, { status: 204 });
      });

      // When
      await apiRequest(path);

      // Then
      const mutationSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(new Response(null, { status: 204 }));
      await apiRequest("/api/v1/admin/boards/board-1/roster", {
        method: "POST",
      });
      const requestInit = mutationSpy.mock.lastCall?.[1];
      expect(new Headers(requestInit?.headers).get("X-XSRF-TOKEN")).toBe(
        "materialized-token",
      );
    },
  );

  it("refreshes the token after a mutation rotates the cookie", async () => {
    // Given
    setDocumentCookie("XSRF-TOKEN=initial-token; Path=/");
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementationOnce(async () => {
        setDocumentCookie("XSRF-TOKEN=rotated-token; Path=/");
        return new Response(null, { status: 204 });
      })
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    // When
    await apiRequest("/api/v1/auth/login", { method: "POST" });
    await apiRequest("/api/v1/admin/boards/board-1/roster", { method: "POST" });

    // Then
    const requestInit = fetchSpy.mock.calls[1]?.[1];
    expect(new Headers(requestInit?.headers).get("X-XSRF-TOKEN")).toBe(
      "rotated-token",
    );
  });

  it.each([
    [403, "csrf_invalid"],
    [409, "signer_stale"],
    [429, "rate_limited"],
  ] as const)("does not replay a failed %i mutation", async (status, code) => {
    // Given
    setDocumentCookie("XSRF-TOKEN=sanitized-token; Path=/");
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          code,
          message: "private roster and signature details",
        }),
        {
          headers: { "Content-Type": "application/json", "Retry-After": "17" },
          status,
        },
      ),
    );

    // When
    const request = apiRequest("/api/v1/public/signing-session/signature", {
      method: "POST",
    });

    // Then
    await expect(request).rejects.toBeInstanceOf(ApiError);
    expect(fetchSpy).toHaveBeenCalledOnce();
  });
});

describe("apiRequest error normalization", () => {
  beforeEach(() => {
    setDocumentCookie("XSRF-TOKEN=sanitized-token; Path=/");
    setCsrfToken(null);
    vi.restoreAllMocks();
  });

  it.each([
    [
      403,
      "csrf_invalid",
      "CSRF_INVALID",
      "요청을 확인할 수 없습니다. 페이지를 새로고침해 주세요.",
    ],
    [409, "signer_stale", "SIGNER_STALE", "서명 정보를 다시 입력해 주세요."],
    [
      404,
      "link_invalid",
      "LINK_INVALID",
      "이 링크에서는 서명을 진행할 수 없습니다.",
    ],
    [409, "board_closed", "BOARD_CLOSED", "현재 서명을 진행할 수 없습니다."],
  ] as const)(
    "maps %s to a generic UI state",
    async (status, backendCode, code, message) => {
      // Given
      vi.spyOn(globalThis, "fetch").mockResolvedValue(
        new Response(
          JSON.stringify({
            code: backendCode,
            message: "private roster and signature details",
          }),
          {
            headers: { "Content-Type": "application/json" },
            status,
          },
        ),
      );

      // When
      const request = apiRequest("/api/v1/public/signing-session/signature", {
        method: "POST",
      });

      // Then
      await expect(request).rejects.toEqual(
        new ApiError(code, message, status),
      );
    },
  );

  it("maps Retry-After without exposing backend text", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "rate_limited",
          message: "participant Kim is blocked",
        }),
        {
          headers: { "Content-Type": "application/json", "Retry-After": "23" },
          status: 429,
        },
      ),
    );

    // When
    const request = apiRequest("/api/v1/public/links/share-token/identify", {
      method: "POST",
    });

    // Then
    await expect(request).rejects.toMatchObject({
      code: "RATE_LIMITED",
      message: "요청이 많습니다. 잠시 후 다시 시도해 주세요.",
      retryAfterSeconds: 23,
    });
    await expect(request).rejects.not.toMatchObject({
      message: expect.stringContaining("Kim"),
    });
  });

  it("normalizes malformed JSON without replaying the signature mutation", async () => {
    // Given
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("{", {
        headers: { "Content-Type": "application/json" },
        status: 409,
      }),
    );

    // When
    const request = apiRequest("/api/v1/public/signing-session/signature", {
      method: "POST",
    });

    // Then
    await expect(request).rejects.toEqual(
      new ApiError(
        "CONFLICT",
        "현재 상태가 변경되었습니다. 다시 시도해 주세요.",
        409,
      ),
    );
    expect(fetchSpy).toHaveBeenCalledOnce();
  });
});
