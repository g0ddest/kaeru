import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount((n) => n + 1)}>Нажато {count}</button>;
}

describe("test harness", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("brings the jest-dom matchers", () => {
    render(<Counter />);
    expect(screen.getByRole("button", { name: "Нажато 0" })).toBeInTheDocument();
  });

  // Debounced screens are tested with fake timers; Testing Library needs the global `jest` shim for that.
  it("lets user-event click while timers are fake", async () => {
    vi.useFakeTimers();
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    render(<Counter />);
    await user.click(screen.getByRole("button"));
    expect(screen.getByRole("button")).toHaveTextContent("Нажато 1");
  });

  it("leaves a page and stored values behind", () => {
    render(<Counter />);
    window.localStorage.setItem("kaeru.harness", "1");
    window.sessionStorage.setItem("kaeru.harness", "1");
    expect(window.localStorage.getItem("kaeru.harness")).toBe("1");
  });

  it("starts the next test with an empty page and empty storage", () => {
    expect(document.body).toBeEmptyDOMElement();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});
