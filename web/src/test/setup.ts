import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// Testing Library advances fake timers only through a global `jest`, and Vitest has none. Without
// this, every `await user.*` under vi.useFakeTimers() waits on a faked setTimeout(0) forever.
(globalThis as unknown as { jest: unknown }).jest = { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) };

afterEach(() => {
  cleanup();
  // Stores persist in web storage; every test starts from an empty browser.
  window.localStorage.clear();
  window.sessionStorage.clear();
});
