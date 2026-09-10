import { describe, expect, it } from "vitest";
import {
  assertDebounceUnplaceEvidence,
  assertQueuedCancellationEvidence,
  assertRequiredScenarioCoverage,
  assertSafeReplacementEvidence,
} from "./board-signature-ink-color.driver-observability.ts";

describe("save-state real driver observability", () => {
  it("Given A1 failure provenance is lost When the aggregate flag is false Then the driver rejects the false success", () => {
    expect(() =>
      assertQueuedCancellationEvidence({
        failureProvenanceLost: true,
        patchStatusesBeforeRecovery: [200, 500, 200],
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        unresolved: { startDisabled: true, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("failure provenance");
  });

  it("Given A2 remains unresolved When Start is enabled Then the driver rejects the unsafe intermediate state", () => {
    expect(() =>
      assertQueuedCancellationEvidence({
        failureProvenanceLost: false,
        patchStatusesBeforeRecovery: [200, 500, 200],
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        unresolved: { startDisabled: false, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("Start disabled while A2 is unresolved");
  });

  it("Given the queued responses are out of order When the terminal UI looks right Then the driver rejects the network claim", () => {
    expect(() =>
      assertQueuedCancellationEvidence({
        failureProvenanceLost: false,
        patchStatusesBeforeRecovery: [500, 200, 200],
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        unresolved: { startDisabled: true, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("responses must be ordered");
  });

  it("Given A2 200 masks A1 failure When no A3 has run Then the driver rejects terminal saved UI", () => {
    expect(() =>
      assertQueuedCancellationEvidence({
        failureProvenanceLost: false,
        patchStatusesBeforeRecovery: [200, 500, 200],
        recovery: {
          sameOwnerAttemptStartedAfterFailure: false,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장됨" },
        unresolved: { startDisabled: true, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("malformed queued cancellation evidence");
  });

  it("Given A1 is replaced before flush When it still PATCHes or leaves pending state Then the driver rejects the phantom success", () => {
    expect(() =>
      assertSafeReplacementEvidence({
        phantomPending: true,
        replacedAttemptPatchRequests: 1,
        terminal: { startDisabled: false, status: "저장됨" },
        violation: false,
      }),
    ).toThrow("replaced A1 must not PATCH");
  });

  it("Given A1 never PATCHes When replacement leaves phantom pending Then the driver rejects the terminal claim", () => {
    expect(() =>
      assertSafeReplacementEvidence({
        phantomPending: true,
        replacedAttemptPatchRequests: 0,
        terminal: { startDisabled: false, status: "저장됨" },
        violation: false,
      }),
    ).toThrow("phantom pending");
  });

  it("Given debounce unplace PATCH 200 When terminal UI or backend read-back is missing Then the driver rejects the response-count success", () => {
    expect(() =>
      assertDebounceUnplaceEvidence({
        backendUnplaced: false,
        foreignSuccess: null,
        held: { startDisabled: true, status: "저장 중" },
        layoutPatchStatus: 200,
        recovery: null,
        terminal: { startDisabled: true, status: "저장 중" },
        violation: false,
      }),
    ).toThrow("PATCH 200 requires terminal saved UI and backend read-back");
  });

  it("Given debounce unplace PATCH 500 When foreign success masks failure Then the driver rejects recovery without a real same-owner retry", () => {
    expect(() =>
      assertDebounceUnplaceEvidence({
        backendUnplaced: false,
        foreignSuccess: { startDisabled: false, status: "저장됨" },
        held: { startDisabled: true, status: "저장 중" },
        layoutPatchStatus: 500,
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("foreign success must not recover layout failure");
  });

  it("Given debounce unplace PATCH 500 remains failed When no same-owner retry ran Then the driver requires actual recovery", () => {
    expect(() =>
      assertDebounceUnplaceEvidence({
        backendUnplaced: false,
        foreignSuccess: { startDisabled: false, status: "저장 실패" },
        held: { startDisabled: true, status: "저장 중" },
        layoutPatchStatus: 500,
        recovery: null,
        terminal: { startDisabled: false, status: "저장 실패" },
        violation: false,
      }),
    ).toThrow("malformed debounce unplace evidence");
  });

  it("accepts decisive queued cancellation, replacement, and debounce evidence", () => {
    expect(() =>
      assertQueuedCancellationEvidence({
        failureProvenanceLost: false,
        patchStatusesBeforeRecovery: [200, 500, 200],
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        unresolved: { startDisabled: true, status: "저장 실패" },
        violation: false,
      }),
    ).not.toThrow();
    expect(() =>
      assertDebounceUnplaceEvidence({
        backendUnplaced: true,
        foreignSuccess: { startDisabled: false, status: "저장 실패" },
        held: { startDisabled: true, status: "저장 중" },
        layoutPatchStatus: 500,
        recovery: {
          sameOwnerAttemptStartedAfterFailure: true,
          startDisabled: false,
          status: "저장됨",
        },
        terminal: { startDisabled: false, status: "저장 실패" },
        violation: false,
      }),
    ).not.toThrow();
    expect(() =>
      assertSafeReplacementEvidence({
        phantomPending: false,
        replacedAttemptPatchRequests: 0,
        terminal: { startDisabled: false, status: "저장됨" },
        violation: false,
      }),
    ).not.toThrow();
    expect(() =>
      assertDebounceUnplaceEvidence({
        backendUnplaced: true,
        foreignSuccess: null,
        held: { startDisabled: true, status: "저장 중" },
        layoutPatchStatus: 200,
        recovery: null,
        terminal: { startDisabled: false, status: "저장됨" },
        violation: false,
      }),
    ).not.toThrow();
  });

  it("requires every retained real-browser matrix scenario to pass", () => {
    expect(() =>
      assertRequiredScenarioCoverage({
        "safe-preflush-replacement": "passed",
      }),
    ).toThrow("queued-cancellation");
    expect(() =>
      assertRequiredScenarioCoverage({
        "color-retry-title-success-200": "passed",
        "color-retry-title-success-500": "passed",
        "debounce-unplace-200": "passed",
        "debounce-unplace-500-recovery": "passed",
        "held-layout-color": "passed",
        "layout-foreign-failure": "passed",
        "queued-cancellation": "passed",
        "safe-preflush-replacement": "passed",
        "stale-a-b-a-200": "passed",
        "stale-a-b-a-500": "passed",
        "title-foreign-failure": "passed",
      }),
    ).not.toThrow();
  });

  describe("untrusted real-driver evidence boundary", () => {
    it.each([
      {
        assertion: assertDebounceUnplaceEvidence,
        evidence: {
          backendUnplaced: true,
          foreignSuccess: null,
          held: { startDisabled: true, status: "저장 중" },
          layoutPatchStatus: 500,
          recovery: { startDisabled: false, status: "저장됨" },
          terminal: { startDisabled: false, status: "저장 실패" },
          violation: false,
        },
        message: "malformed debounce unplace evidence",
        name: "500 recovery without same-owner provenance",
      },
      {
        assertion: assertDebounceUnplaceEvidence,
        evidence: {
          backendUnplaced: true,
          foreignSuccess: { startDisabled: true, status: "저장 중" },
          held: { startDisabled: true, status: "저장 중" },
          layoutPatchStatus: 500,
          recovery: {
            sameOwnerAttemptStartedAfterFailure: true,
            startDisabled: false,
            status: "저장됨",
          },
          terminal: { startDisabled: false, status: "저장 실패" },
          violation: false,
        },
        message: "foreign success",
        name: "foreign completion still saving",
      },
      {
        assertion: assertDebounceUnplaceEvidence,
        evidence: {
          backendUnplaced: true,
          foreignSuccess: null,
          layoutPatchStatus: 200,
          recovery: null,
          terminal: { startDisabled: false, status: "저장됨" },
          violation: false,
        },
        message: "malformed debounce unplace evidence",
        name: "missing held Start-lock observation",
      },
      {
        assertion: assertQueuedCancellationEvidence,
        evidence: {
          patchStatusesBeforeRecovery: [200, 500, 200],
          recovery: {
            sameOwnerAttemptStartedAfterFailure: true,
            startDisabled: false,
            status: "저장됨",
          },
          terminal: { startDisabled: false, status: "저장 실패" },
          unresolved: { startDisabled: true, status: "저장 실패" },
          violation: false,
        },
        message: "malformed queued cancellation evidence",
        name: "missing failure provenance flag",
      },
      {
        assertion: assertSafeReplacementEvidence,
        evidence: {
          replacedAttemptPatchRequests: 0,
          terminal: { startDisabled: false, status: "저장됨" },
          violation: false,
        },
        message: "malformed safe replacement evidence",
        name: "missing phantom-pending flag",
      },
      {
        assertion: assertDebounceUnplaceEvidence,
        evidence: {
          backendUnplaced: true,
          foreignSuccess: null,
          held: { startDisabled: true, status: "저장 중" },
          layoutPatchStatus: 201,
          recovery: null,
          terminal: { startDisabled: false, status: "저장됨" },
          violation: false,
        },
        message: "malformed debounce unplace evidence",
        name: "layout PATCH 201",
      },
      {
        assertion: assertDebounceUnplaceEvidence,
        evidence: {
          backendUnplaced: true,
          foreignSuccess: null,
          held: { startDisabled: true, status: "저장 중" },
          layoutPatchStatus: 200,
          recovery: null,
          terminal: { status: "저장됨" },
          violation: false,
        },
        message: "malformed debounce unplace evidence",
        name: "missing terminal Start state",
      },
    ])("rejects $name", ({ assertion, evidence, message }) => {
      expect(() => assertion(evidence)).toThrow(message);
    });
  });
});
