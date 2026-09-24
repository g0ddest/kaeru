import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TOAST_MS, TOAST_WITH_ACTION_MS, ToastProvider, useToast } from "./Toast";

// Copy: decision 6 (undo after unmarking) and the Android network error.
const UNMARKED = "Серия 3 отмечена непросмотренной";
const OFFLINE = "Нет соединения. Проверьте интернет";

function Trigger({ onUndo }: { onUndo?: () => void }) {
  const toast = useToast();
  return (
    <>
      <button
        type="button"
        onClick={() => toast.show(UNMARKED, onUndo ? { label: "Отменить", onClick: onUndo } : undefined)}
      >
        Снять отметку
      </button>
      <button type="button" onClick={() => toast.show(OFFLINE)}>
        Ошибка
      </button>
    </>
  );
}

function renderToasts(onUndo?: () => void) {
  return render(
    <ToastProvider>
      <Trigger onUndo={onUndo} />
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("toasts", () => {
  it("speak through a polite live region", () => {
    renderToasts();
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toBeEmptyDOMElement();
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    expect(region).toHaveTextContent(OFFLINE);
  });

  it("hide a plain toast after 4 seconds", () => {
    renderToasts();
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    act(() => {
      vi.advanceTimersByTime(TOAST_MS - 1);
    });
    expect(screen.getByText(OFFLINE)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText(OFFLINE)).toBeNull();
  });

  it("keep a toast with an action for 8 seconds", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS - 1);
    });
    expect(screen.getByText(UNMARKED)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("run the action once and close", () => {
    const onUndo = vi.fn();
    renderToasts(onUndo);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    fireEvent.click(screen.getByRole("button", { name: "Отменить" }));
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("show one at a time, the newest", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    fireEvent.click(screen.getByRole("button", { name: "Ошибка" }));
    expect(screen.getByRole("status")).toHaveTextContent(OFFLINE);
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("wait while the viewer is on the toast", () => {
    renderToasts(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Снять отметку" }));
    const undo = screen.getByRole("button", { name: "Отменить" });
    act(() => {
      undo.focus();
    });
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS * 2);
    });
    expect(screen.getByText(UNMARKED)).toBeInTheDocument();
    act(() => {
      undo.blur();
    });
    act(() => {
      vi.advanceTimersByTime(TOAST_WITH_ACTION_MS);
    });
    expect(screen.queryByText(UNMARKED)).toBeNull();
  });

  it("need the provider", () => {
    expect(() => render(<Trigger />)).toThrow(/ToastProvider/);
  });
});
