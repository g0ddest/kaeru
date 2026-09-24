import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { WatchPlaceholderScreen } from "./WatchPlaceholderScreen";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/watch/:id/:episode" element={<WatchPlaceholderScreen />} />
        <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("WatchPlaceholderScreen", () => {
  it("says the player is coming and leads back to the title", async () => {
    renderAt("/watch/1535/3");
    expect(screen.getByRole("heading", { name: "Плеер появится в следующем обновлении" })).toBeInTheDocument();
    expect(screen.getByText("3 серия")).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "Назад к тайтлу" });
    expect(back).toHaveAttribute("href", "/anime/1535");
    await userEvent.setup().click(back);
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("leaves out the episode line for a malformed episode", () => {
    renderAt("/watch/1535/x");
    expect(screen.queryByText(/серия/)).toBeNull();
  });
});
