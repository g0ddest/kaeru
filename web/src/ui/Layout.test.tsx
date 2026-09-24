import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sessionStore, type Session } from "../auth/session";
import { Layout } from "./Layout";

const SESSION: Session = {
  account: { id: 42, nickname: "Vitaliy", avatar: null },
  tokens: { accessToken: "tok", refreshToken: "ref", expiresIn: 86400, createdAt: Math.floor(Date.now() / 1000) },
};

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<p>Главная страница</p>} />
          <Route path="/list" element={<p>Страница списка</p>} />
          <Route path="/search" element={<p>Страница поиска</p>} />
          <Route path="/settings" element={<p>Страница настроек</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  sessionStore.setSession(SESSION);
});

afterEach(() => {
  sessionStore.signOut();
});

describe("Layout", () => {
  it("renders the page inside the main landmark", () => {
    renderAt("/list");
    expect(within(screen.getByRole("main")).getByText("Страница списка")).toBeInTheDocument();
  });

  it("offers the sections in the sidebar and the tab bar, marking the current one", () => {
    renderAt("/list");
    const current = screen.getAllByRole("link", { name: "Мой список" });
    expect(current).toHaveLength(2);
    for (const link of current) expect(link).toHaveAttribute("aria-current", "page");
    for (const name of ["Главная", "Поиск"]) {
      const links = screen.getAllByRole("link", { name });
      expect(links).toHaveLength(2);
      for (const link of links) expect(link).not.toHaveAttribute("aria-current");
    }
  });

  it("groups «Мой список» under «Библиотека» in the sidebar", () => {
    renderAt("/");
    const group = screen.getByRole("group", { name: "Библиотека" });
    expect(within(group).getByRole("link", { name: "Мой список" })).toHaveAttribute("href", "/list");
  });

  it("orders the tab bar as on Android: Главная, Мой список, Поиск", () => {
    const { container } = renderAt("/");
    const sidebar = container.querySelector("aside") as HTMLElement;
    const tabbar = screen
      .getAllByRole("navigation", { name: "Разделы" })
      .find((nav) => !sidebar.contains(nav)) as HTMLElement;
    expect(within(tabbar).getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Главная",
      "Мой список",
      "Поиск",
    ]);
  });

  it("opens settings from the account row, labelled for screen readers", async () => {
    renderAt("/");
    const account = screen.getAllByRole("link", { name: "Аккаунт и настройки" });
    expect(account).toHaveLength(2);
    for (const link of account) expect(link).toHaveAttribute("href", "/settings");
    expect(screen.getByText("Vitaliy")).toBeInTheDocument();
    await userEvent.setup().click(account[0]);
    expect(screen.getByText("Страница настроек")).toBeInTheDocument();
  });

  it("shows the Shikimori avatar when there is one", () => {
    const avatar = "https://shikimori.io/system/users/x160/42.png";
    sessionStore.setSession({ ...SESSION, account: { ...SESSION.account, avatar } });
    const { container } = renderAt("/");
    const images = container.querySelectorAll("img.avatar");
    expect(images).toHaveLength(2);
    for (const image of images) {
      expect(image).toHaveAttribute("src", avatar);
      expect(image).toHaveAttribute("alt", "");
    }
  });

  it("moves between sections", async () => {
    renderAt("/");
    await userEvent.setup().click(screen.getAllByRole("link", { name: "Поиск" })[0]);
    expect(screen.getByText("Страница поиска")).toBeInTheDocument();
    for (const link of screen.getAllByRole("link", { name: "Поиск" })) {
      expect(link).toHaveAttribute("aria-current", "page");
    }
  });

  it("offers a skip link to the content", () => {
    renderAt("/");
    expect(screen.getByRole("link", { name: "Перейти к содержимому" })).toHaveAttribute("href", "#main");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main");
  });
});
