import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Global styles first: the stylesheets that screens import through App must come after them in the bundle.
import "./ui/tokens.css";
import "./ui/base.css";
import "./ui/components.css";
import { App } from "./app/App";
import { canonicalAddress, restoreDeepLink } from "./app/bootstrap";

// "name." is another origin to the browser; go to the usual address before anything is stored.
const canonical = canonicalAddress(window.location);
if (canonical !== null) {
  window.location.replace(canonical);
} else {
  // Before the router reads the address: /?p=%2Fanime%2F1535 becomes /anime/1535.
  restoreDeepLink(window.location, window.history);

  const root = document.getElementById("root");
  if (!root) throw new Error("index.html has no #root element");

  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
