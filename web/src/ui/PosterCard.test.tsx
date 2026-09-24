import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Card } from "../domain/feed";
import { SecondaryButton } from "./Button";
import { PosterCard, PosterGrid, type PosterCardProps } from "./PosterCard";
import { Shelf } from "./Shelf";

const CARD: Card = {
  key: "52991",
  animeId: 52991,
  title: "Фрирен, провожающая в последний путь",
  posterUrl: "https://shikimori.io/uploads/poster/animes/52991/main_alt.jpeg",
  badge: "3 серия",
  subtitle: "осталось 12 мин",
  progress: 0.5,
};

function renderCard(props: Partial<PosterCardProps> & { card: Card }) {
  return render(
    <MemoryRouter>
      <PosterCard {...props} />
    </MemoryRouter>,
  );
}

describe("PosterCard", () => {
  it("links to the title page with the artwork, badge and subtitle", () => {
    const { container } = renderCard({ card: CARD });
    const link = screen.getByRole("link", { name: /Фрирен, провожающая в последний путь/ });
    expect(link).toHaveAttribute("href", "/anime/52991");
    expect(within(link).getByText("3 серия")).toBeInTheDocument();
    expect(within(link).getByText("осталось 12 мин")).toBeInTheDocument();
    const image = container.querySelector("img");
    expect(image).toHaveAttribute("src", CARD.posterUrl);
    // The title sits beside the artwork, so the image itself is decorative.
    expect(image).toHaveAttribute("alt", "");
  });

  it("shows the title's first letter when there is no poster", () => {
    const { container } = renderCard({ card: { ...CARD, posterUrl: null } });
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("Ф")).toHaveAttribute("aria-hidden", "true");
  });

  it("upper-cases the letter the Russian way", () => {
    renderCard({ card: { ...CARD, title: "ёлка", posterUrl: null } });
    expect(screen.getByText("Ё")).toBeInTheDocument();
  });

  it("falls back to the letter when the image fails", () => {
    const { container } = renderCard({ card: CARD });
    fireEvent.error(container.querySelector("img") as HTMLImageElement);
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("Ф")).toBeInTheDocument();
  });

  it("starts over when the card gets another poster", () => {
    // A card keeps its key while a later load brings a new poster, e.g. once a failed GraphQL batch succeeds.
    const { container, rerender } = renderCard({ card: CARD });
    fireEvent.error(container.querySelector("img") as HTMLImageElement);
    expect(container.querySelector("img")).toBeNull();
    const next = "https://shikimori.io/uploads/poster/animes/52991/main_2x.jpeg";
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, posterUrl: next }} />
      </MemoryRouter>,
    );
    const image = container.querySelector("img") as HTMLImageElement;
    expect(image).toHaveAttribute("src", next);
    fireEvent.load(image);
    expect(image).toHaveAttribute("data-loaded", "true");
    // The next poster fades in again rather than appearing before it has loaded.
    const later = "https://shikimori.io/uploads/poster/animes/52991/main_alt_2x.jpeg";
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, posterUrl: later }} />
      </MemoryRouter>,
    );
    expect(container.querySelector("img")).toHaveAttribute("data-loaded", "false");
  });

  it("draws the progress strip only for a started episode", () => {
    const { container, rerender } = renderCard({ card: CARD });
    expect(container.querySelector<HTMLElement>(".progress-strip__fill")?.style.width).toBe("50%");
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, progress: null }} />
      </MemoryRouter>,
    );
    expect(container.querySelector(".progress-strip")).toBeNull();
    rerender(
      <MemoryRouter>
        <PosterCard card={{ ...CARD, progress: 0 }} />
      </MemoryRouter>,
    );
    expect(container.querySelector(".progress-strip")).toBeNull();
  });

  it("keeps the footer control outside the link", () => {
    renderCard({ card: CARD, layout: "grid", footer: <SecondaryButton compact>В планы</SecondaryButton> });
    expect(screen.getByRole("link")).not.toContainElement(screen.getByRole("button", { name: "В планы" }));
  });

  it("can lead somewhere other than the title page", () => {
    renderCard({ card: CARD, to: "/watch/52991/3" });
    expect(screen.getByRole("link")).toHaveAttribute("href", "/watch/52991/3");
  });
});

describe("Shelf", () => {
  it("is a region named by its header, one list item per card", () => {
    render(
      <MemoryRouter>
        <Shelf title="Продолжить" action={{ label: "Всё", to: "/list" }}>
          <PosterCard card={CARD} />
          <PosterCard card={{ ...CARD, key: "1535", animeId: 1535, title: "Тетрадь смерти" }} />
        </Shelf>
      </MemoryRouter>,
    );
    const shelf = screen.getByRole("region", { name: "Продолжить" });
    expect(within(shelf).getByRole("heading", { level: 2, name: "Продолжить" })).toBeInTheDocument();
    expect(within(shelf).getAllByRole("listitem")).toHaveLength(2);
    expect(within(shelf).getByRole("link", { name: "Всё" })).toHaveAttribute("href", "/list");
  });

  it("puts extra controls between the header and the row", () => {
    render(
      <MemoryRouter>
        <Shelf title="Популярное в сезоне" extra={<p>Выбор сезона</p>}>
          <PosterCard card={CARD} />
        </Shelf>
      </MemoryRouter>,
    );
    const shelf = screen.getByRole("region", { name: "Популярное в сезоне" });
    expect(within(shelf).getByText("Выбор сезона")).toBeInTheDocument();
  });
});

describe("PosterGrid", () => {
  it("lists its cards and skips empty children", () => {
    render(
      <MemoryRouter>
        <PosterGrid label="Результаты поиска">
          <PosterCard card={CARD} layout="grid" />
          {null}
        </PosterGrid>
      </MemoryRouter>,
    );
    const list = screen.getByRole("list", { name: "Результаты поиска" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
  });
});
