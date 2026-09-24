import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { CLIENT_ID } from "../config";
import { SignInScreen } from "./SignInScreen";

describe("SignInScreen", () => {
  it("sends the browser to Shikimori's authorize page", async () => {
    const navigateTo = vi.fn<(url: string) => void>();
    render(
      <MemoryRouter>
        <SignInScreen message={null} navigateTo={navigateTo} />
      </MemoryRouter>,
    );
    const button = screen.getByRole("button", { name: "Войти через Shikimori" });
    await userEvent.setup().click(button);

    expect(navigateTo).toHaveBeenCalledTimes(1);
    const url = new URL(navigateTo.mock.calls[0][0]);
    // web-map 3: /oauth/authorize?client_id&redirect_uri&response_type=code&scope=user_rates&state
    expect(url.origin + url.pathname).toBe("https://shikimori.io/oauth/authorize");
    expect(url.searchParams.get("client_id")).toBe(CLIENT_ID);
    expect(url.searchParams.get("redirect_uri")).toBe(`${window.location.origin}/auth`);
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("scope")).toBe("user_rates");
    // 32 random bytes, base64url without padding.
    expect(url.searchParams.get("state")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    // The page is leaving: a second press must not start another authorization.
    expect(button).toBeDisabled();
  });

  it("asks to come back to the page that was open", async () => {
    const begin = vi.fn((returnTo: string) => `https://shikimori.io/oauth/authorize?r=${encodeURIComponent(returnTo)}`);
    render(
      <MemoryRouter initialEntries={["/anime/5?tab=1#episodes"]}>
        <SignInScreen message={null} begin={begin} navigateTo={() => undefined} />
      </MemoryRouter>,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Войти через Shikimori" }));
    expect(begin).toHaveBeenCalledWith("/anime/5?tab=1#episodes");
  });

  it("shows why the viewer is back", () => {
    render(
      <MemoryRouter>
        <SignInScreen message="Сессия истекла, войдите снова" navigateTo={() => undefined} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла, войдите снова");
  });
});
