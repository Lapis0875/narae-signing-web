import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { AppProviders } from "./app/AppProviders.tsx"
import { AppRouter } from "./routes/AppRouter.tsx"
import "./styles.css"

const root = document.getElementById("root")

if (root !== null) {
  createRoot(root).render(
    <StrictMode>
      <AppProviders>
        <AppRouter />
      </AppProviders>
    </StrictMode>,
  )
}
