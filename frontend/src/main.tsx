import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppProviders } from "./app/AppProviders.tsx";
import { AppRouter } from "./routes/AppRouter.tsx";
import "./styles.css";

declare global {
  interface ImportMetaEnv {
    readonly VITE_ENABLE_REACT_DEVTOOLS?: string;
  }
}

if (
  import.meta.env.DEV &&
  import.meta.env.VITE_ENABLE_REACT_DEVTOOLS === "1"
) {
  void import("react-grab");
  void import("react-scan");
}

const root = document.getElementById("root");

if (root !== null) {
  createRoot(root).render(
    <StrictMode>
      <AppProviders>
        <AppRouter />
      </AppProviders>
    </StrictMode>,
  );
}
