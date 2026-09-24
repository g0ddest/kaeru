import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./ui/tokens.css";
import "./ui/base.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root element");

// A bare page until the app shell exists (Task 8 replaces this file); it proves the font, palette and build.
createRoot(root).render(
  <StrictMode>
    <h1 className="t-display">Kaeru</h1>
  </StrictMode>,
);
