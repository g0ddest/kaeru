import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { IconArrowDropDown } from "./icons";
import { Pill, PillGroup, type PillOption } from "./Pill";

// Season chips: prev, current, next (web-map 1), radio semantics (HomeDiscover.kt:137-153).
const SEASONS: PillOption<string>[] = [
  { value: "summer_2026", label: "Лето 2026" },
  { value: "fall_2026", label: "Осень 2026" },
  { value: "winter_2027", label: "Зима 2027" },
];

function Seasons() {
  const [value, setValue] = useState("fall_2026");
  return <PillGroup kind="radio" label="Сезон" options={SEASONS} value={value} onChange={setValue} />;
}

function Statuses() {
  const [value, setValue] = useState("watching");
  return (
    <PillGroup
      kind="tab"
      label="Статус"
      options={[
        { value: "watching", label: "Смотрю 12" },
        { value: "planned", label: "В планах 3" },
      ]}
      value={value}
      onChange={setValue}
      panelId="list-panel"
      idPrefix="status"
    />
  );
}

describe("PillGroup as a radiogroup", () => {
  it("names the group and checks the chosen pill", () => {
    render(<Seasons />);
    const group = screen.getByRole("radiogroup", { name: "Сезон" });
    expect(within(group).getAllByRole("radio").map((radio) => radio.textContent)).toEqual([
      "Лето 2026",
      "Осень 2026",
      "Зима 2027",
    ]);
    const autumn = screen.getByRole("radio", { name: "Осень 2026" });
    expect(autumn).toHaveAttribute("aria-checked", "true");
    expect(autumn).toHaveClass("pill--selected");
    expect(screen.getByRole("radio", { name: "Лето 2026" })).toHaveAttribute("aria-checked", "false");
  });

  it("keeps a single tab stop, on the chosen pill", () => {
    render(<Seasons />);
    expect(screen.getByRole("radio", { name: "Осень 2026" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Лето 2026" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("radio", { name: "Зима 2027" })).toHaveAttribute("tabindex", "-1");
  });

  it("chooses by click", async () => {
    render(<Seasons />);
    await userEvent.setup().click(screen.getByRole("radio", { name: "Зима 2027" }));
    expect(screen.getByRole("radio", { name: "Зима 2027" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Осень 2026" })).toHaveAttribute("aria-checked", "false");
  });

  it("moves with the arrow keys, wrapping around, and with Home and End", async () => {
    const user = userEvent.setup();
    render(<Seasons />);
    await user.click(screen.getByRole("radio", { name: "Осень 2026" }));
    const summer = screen.getByRole("radio", { name: "Лето 2026" });
    const winter = screen.getByRole("radio", { name: "Зима 2027" });

    await user.keyboard("{ArrowRight}");
    expect(winter).toHaveAttribute("aria-checked", "true");
    expect(winter).toHaveFocus();

    await user.keyboard("{ArrowRight}");
    expect(summer).toHaveAttribute("aria-checked", "true");
    expect(summer).toHaveFocus();

    await user.keyboard("{ArrowLeft}");
    expect(winter).toHaveFocus();

    await user.keyboard("{Home}");
    expect(summer).toHaveAttribute("aria-checked", "true");

    await user.keyboard("{End}");
    expect(winter).toHaveAttribute("aria-checked", "true");
  });
});

describe("PillGroup as a tablist", () => {
  it("marks the selected tab and the panel it controls", async () => {
    render(<Statuses />);
    const tablist = screen.getByRole("tablist", { name: "Статус" });
    const watching = within(tablist).getByRole("tab", { name: "Смотрю 12" });
    expect(watching).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveAttribute("aria-controls", "list-panel");
    expect(watching).toHaveAttribute("id", "status-watching");
    await userEvent.setup().click(screen.getByRole("tab", { name: "В планах 3" }));
    expect(screen.getByRole("tab", { name: "В планах 3" })).toHaveAttribute("aria-selected", "true");
    expect(watching).toHaveAttribute("aria-selected", "false");
  });

  it("does not move on up and down arrows (a horizontal tablist)", async () => {
    const user = userEvent.setup();
    render(<Statuses />);
    await user.click(screen.getByRole("tab", { name: "Смотрю 12" }));
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("tab", { name: "Смотрю 12" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("Pill", () => {
  it("marks selection and hides its trailing glyph", () => {
    render(
      <Pill selected trailing={<IconArrowDropDown />} aria-haspopup="menu">
        Смотрю
      </Pill>,
    );
    const pill = screen.getByRole("button", { name: "Смотрю" });
    expect(pill).toHaveClass("pill", "pill--selected");
    expect(pill).toHaveAttribute("aria-haspopup", "menu");
    expect(pill.querySelector(".pill__trailing")).toHaveAttribute("aria-hidden", "true");
  });
});
