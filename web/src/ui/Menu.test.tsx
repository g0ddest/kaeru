import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MenuButton, type MenuItem } from "./Menu";

afterEach(cleanup);

function statusItems(pick: (key: string) => void): MenuItem[] {
  return [
    { key: "watching", label: "Смотрю", checked: true, onSelect: () => pick("watching") },
    { key: "planned", label: "В планах", checked: false, onSelect: () => pick("planned") },
    { key: "dropped", label: "Брошено", checked: false, onSelect: () => pick("dropped") },
  ];
}

function renderStatus(options: { disabled?: boolean } = {}) {
  const pick = vi.fn();
  render(
    <>
      <MenuButton label="Смотрю" items={statusItems(pick)} disabled={options.disabled ?? false} />
      <button type="button">Снаружи</button>
    </>,
  );
  return pick;
}

describe("MenuButton", () => {
  it("opens a menu named by its button with the ticked choice focused", async () => {
    const user = userEvent.setup();
    renderStatus();
    const trigger = screen.getByRole("button", { name: "Смотрю" });
    expect(trigger).toHaveAttribute("aria-haspopup", "menu");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("menu", { name: "Смотрю" })).toBeInTheDocument();
    expect(screen.getAllByRole("menuitemradio").map((item) => item.textContent)).toEqual(["Смотрю", "В планах", "Брошено"]);
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemradio", { name: "В планах" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
  });

  it("moves with the arrow keys, Home and End, and picks with Enter", async () => {
    const user = userEvent.setup();
    const pick = renderStatus();
    await user.tab();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();

    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "В планах" })).toHaveFocus();
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{ArrowUp}");
    expect(screen.getByRole("menuitemradio", { name: "Брошено" })).toHaveFocus();
    await user.keyboard("{Home}");
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();
    await user.keyboard("{End}{Enter}");

    expect(pick).toHaveBeenCalledWith("dropped");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();
  });

  it("closes on Escape and gives focus back to its button", async () => {
    const user = userEvent.setup();
    const pick = renderStatus();
    await user.click(screen.getByRole("button", { name: "Смотрю" }));

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();
    expect(pick).not.toHaveBeenCalled();
  });

  it("closes when something outside it is pressed", async () => {
    const user = userEvent.setup();
    renderStatus();
    await user.click(screen.getByRole("button", { name: "Смотрю" }));

    await user.click(screen.getByRole("button", { name: "Снаружи" }));

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("offers plain actions as menu items behind an icon button", async () => {
    const user = userEvent.setup();
    const watch = vi.fn();
    const unmark = vi.fn();
    render(
      <MenuButton
        variant="icon"
        ariaLabel="Что сделать с серией 5"
        label={<svg aria-hidden="true" />}
        items={[
          { key: "watch", label: "Смотреть", onSelect: watch },
          { key: "unmark", label: "Отметить непросмотренной", destructive: true, onSelect: unmark },
        ]}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Что сделать с серией 5" }));

    expect(screen.getByRole("menu", { name: "Что сделать с серией 5" })).toBeInTheDocument();
    expect(screen.getAllByRole("menuitem").map((item) => item.textContent)).toEqual(["Смотреть", "Отметить непросмотренной"]);
    await user.click(screen.getByRole("menuitem", { name: "Отметить непросмотренной" }));
    expect(unmark).toHaveBeenCalledTimes(1);
    expect(watch).not.toHaveBeenCalled();
  });

  it("skips a disabled item with the arrows and does not pick it", async () => {
    const user = userEvent.setup();
    const pick = vi.fn();
    render(
      <MenuButton
        label="AniLibria.TV"
        items={[
          { key: "610", label: "AniLibria.TV", checked: true, onSelect: () => pick(610) },
          { key: "609", label: "AniDUB", checked: false, disabled: true, note: "нет серии 8", onSelect: () => pick(609) },
          { key: "1001", label: "Студия Икс", checked: false, onSelect: () => pick(1001) },
        ]}
      />,
    );
    await user.click(screen.getByRole("button", { name: "AniLibria.TV" }));

    const blocked = screen.getByRole("menuitemradio", { name: "AniDUB" });
    expect(blocked).toBeDisabled();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio", { name: "Студия Икс" })).toHaveFocus();
    await user.keyboard("{ArrowUp}");
    expect(screen.getByRole("menuitemradio", { name: "AniLibria.TV" })).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByRole("menuitemradio", { name: "Студия Икс" })).toHaveFocus();

    await user.click(blocked);
    expect(pick).not.toHaveBeenCalled();
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("shows an item's note as a second line that describes it", async () => {
    const user = userEvent.setup();
    render(
      <MenuButton
        label="Озвучка"
        items={[
          { key: "610", label: "AniLibria.TV", checked: true, onSelect: () => undefined },
          { key: "77", label: "Crunchyroll", checked: false, note: "Субтитры", onSelect: () => undefined },
        ]}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Озвучка" }));

    const subtitled = screen.getByRole("menuitemradio", { name: "Crunchyroll" });
    expect(subtitled).toHaveAccessibleDescription("Субтитры");
    expect(within(subtitled).getByText("Субтитры")).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "AniLibria.TV" })).not.toHaveAccessibleDescription();
  });

  it("can be opened and closed by its owner", async () => {
    const user = userEvent.setup();
    function Owned() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Сменить озвучку
          </button>
          <MenuButton label="Смотрю" items={statusItems(() => undefined)} open={open} onOpenChange={setOpen} />
        </>
      );
    }
    render(<Owned />);

    await user.click(screen.getByRole("button", { name: "Сменить озвучку" }));
    expect(screen.getByRole("menu", { name: "Смотрю" })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: "Смотрю" })).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Смотрю" })).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "Смотрю" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("stays shut while disabled", async () => {
    const user = userEvent.setup();
    renderStatus({ disabled: true });
    const trigger = screen.getByRole("button", { name: "Смотрю" });

    expect(trigger).toBeDisabled();
    await user.click(trigger);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
