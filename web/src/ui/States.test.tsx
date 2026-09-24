import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { SkeletonGrid, SkeletonGroup, SkeletonHero, SkeletonShelf } from "./Skeleton";
import { EmptyState, ErrorState, ProgressStrip, SyncingNotice } from "./States";

// Copy: android/src/main/java/app/kaeru/ui/common/library/LibraryTabs.kt:73-100 (web-map 6).
const WATCHING_TITLE = "Вы ничего не смотрите";
const WATCHING_TEXT = "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.";

describe("EmptyState", () => {
  it("shows a title, a text and a link action", async () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route
            path="/"
            element={<EmptyState title={WATCHING_TITLE} text={WATCHING_TEXT} action={{ label: "Найти аниме", to: "/search" }} />}
          />
          <Route path="/search" element={<p>Страница поиска</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByRole("heading", { name: WATCHING_TITLE })).toBeInTheDocument();
    expect(screen.getByText(WATCHING_TEXT)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("link", { name: "Найти аниме" }));
    expect(screen.getByText("Страница поиска")).toBeInTheDocument();
  });

  it("can run an action instead of navigating", async () => {
    const onClick = vi.fn();
    render(<EmptyState title={WATCHING_TITLE} action={{ label: "Найти аниме", onClick }} />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Найти аниме" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("has no control without an action", () => {
    render(<EmptyState title={WATCHING_TITLE} />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });
});

describe("ErrorState", () => {
  it("announces the message and retries with a secondary button", async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Нет соединения. Проверьте интернет" onRetry={onRetry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Нет соединения. Проверьте интернет");
    const retry = screen.getByRole("button", { name: "Повторить" });
    // Retry is never amber (web-map 6, accent discipline).
    expect(retry).toHaveClass("btn--secondary");
    await userEvent.setup().click(retry);
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("offers no retry without a handler", () => {
    render(<ErrorState message="Shikimori недоступен, попробуйте позже" />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("strips and notices", () => {
  it("clamps a progress strip to 0–100 %", () => {
    const { container } = render(
      <>
        <ProgressStrip value={0.25} />
        <ProgressStrip value={1.4} />
        <ProgressStrip value={-1} />
      </>,
    );
    const widths = Array.from(container.querySelectorAll<HTMLElement>(".progress-strip__fill")).map(
      (fill) => fill.style.width,
    );
    expect(widths).toEqual(["25%", "100%", "0%"]);
  });

  it("says the list is syncing", () => {
    render(<SyncingNotice />);
    expect(screen.getByRole("status")).toHaveTextContent("Синхронизируем список с Shikimori…");
  });
});

describe("skeletons", () => {
  it("announce one loading region and hide the blocks", () => {
    const { container } = render(
      <SkeletonGroup>
        <SkeletonHero />
        <SkeletonShelf cards={4} />
        <SkeletonGrid count={6} />
      </SkeletonGroup>,
    );
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-busy", "true");
    expect(region).toHaveTextContent("Загрузка…");
    expect(container.querySelectorAll(".skeleton-shelf .skeleton-card")).toHaveLength(4);
    expect(container.querySelectorAll(".skeleton-grid .skeleton-card")).toHaveLength(6);
    // Only the label speaks. Every other child of the region is hidden, together with the blocks inside it.
    const shapes = Array.from(region.children).filter((child) => !child.classList.contains("visually-hidden"));
    expect(shapes).toHaveLength(3);
    for (const shape of shapes) expect(shape).toHaveAttribute("aria-hidden", "true");
    // A block inside a shelf or grid is hidden by its wrapper, not only by its own attribute.
    const nested = container.querySelectorAll(".skeleton-shelf .skeleton, .skeleton-grid .skeleton");
    expect(nested).toHaveLength(1 + 4 * 2 + 6 * 2);
    for (const block of nested) {
      expect(block.parentElement?.closest('[aria-hidden="true"]')).not.toBeNull();
    }
  });

  it("take a label of their own", () => {
    render(
      <SkeletonGroup label="Ищем аниме…">
        <SkeletonGrid />
      </SkeletonGroup>,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Ищем аниме…");
  });
});
