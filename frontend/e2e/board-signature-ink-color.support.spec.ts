import { expect, test } from "@playwright/test";
import { adminJson } from "./board-signature-ink-color.support.ts";

test("uses issued secure browser state when current-URL filtering hides it", async ({
  page,
}) => {
  // Given: Chromium stores secure state that its HTTP page still exposes to the
  // application, while URL-filtered BrowserContext lookup omits it.
  await page.route("/", (route) => route.fulfill({ body: "<main></main>" }));
  await page.goto("/");
  await page.context().addCookies([
    {
      domain: "127.0.0.1",
      name: "XSRF-TOKEN",
      path: "/",
      secure: true,
      value: crypto.randomUUID(),
    },
  ]);
  const allState = await page.context().cookies();
  const currentUrlState = await page.context().cookies([page.url()]);
  expect(allState.some(({ name }) => name === "XSRF-TOKEN")).toBe(true);
  expect(currentUrlState.some(({ name }) => name === "XSRF-TOKEN")).toBe(
    false,
  );

  let csrfHeaderPresent = false;
  let protectedRequestIssued = false;
  await page.route("**/api/v1/admin/boards/filter-regression", async (route) => {
    protectedRequestIssued = true;
    csrfHeaderPresent =
      (await route.request().headerValue("x-xsrf-token")) !== null;
    await route.fulfill({ body: "{}", contentType: "application/json" });
  });

  // When: the support helper issues a same-origin protected request.
  const result = await adminJson(
    page,
    "/api/v1/admin/boards/filter-regression",
    "PATCH",
    "{}",
  );

  // Then: the protected operation proceeds without exposing the state value.
  expect(result.status).toBe(200);
  expect(protectedRequestIssued).toBe(true);
  expect(csrfHeaderPresent).toBe(true);
});
