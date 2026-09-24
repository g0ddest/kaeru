// The settings switch: android/src/main/java/app/kaeru/ui/common/settings/SettingsSections.kt
// SettingSwitchRow and ui/common/design/KaeruSwitch.kt, as a WAI-ARIA switch.
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Switch } from "./Switch";

const NOTE = "Через 10 секунд начнётся следующая серия. После последней плеер закроется и вернёт на карточку";

function Owned({ initial = false, note, onChange }: { initial?: boolean; note?: string; onChange?: (on: boolean) => void }) {
  const [on, setOn] = useState(initial);
  return (
    <Switch
      label="Пропускать эндинг"
      note={note}
      checked={on}
      onChange={(value) => {
        onChange?.(value);
        setOn(value);
      }}
    />
  );
}

describe("Switch", () => {
  it("is a switch named by its label that says whether it is on", () => {
    render(<Owned initial />);
    const control = screen.getByRole("switch", { name: "Пропускать эндинг" });
    expect(control).toHaveAttribute("aria-checked", "true");
    expect(control).toHaveAttribute("type", "button");
  });

  it("turns on and off with a click", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Owned onChange={onChange} />);
    const control = screen.getByRole("switch", { name: "Пропускать эндинг" });
    expect(control).not.toBeChecked();

    await user.click(control);
    expect(control).toBeChecked();
    await user.click(control);
    expect(control).not.toBeChecked();
    expect(onChange.mock.calls).toEqual([[true], [false]]);
  });

  it("turns with Space and with Enter from the keyboard", async () => {
    const user = userEvent.setup();
    render(<Owned />);
    const control = screen.getByRole("switch", { name: "Пропускать эндинг" });

    await user.tab();
    expect(control).toHaveFocus();
    await user.keyboard(" ");
    expect(control).toBeChecked();
    await user.keyboard("{Enter}");
    expect(control).not.toBeChecked();
  });

  it("is described by its note, which is not part of its name", () => {
    render(<Owned note={NOTE} />);
    const control = screen.getByRole("switch", { name: "Пропускать эндинг" });
    expect(control).toHaveAccessibleDescription(NOTE);
    expect(screen.getByText(NOTE)).toBeVisible();
  });

  it("wears the accent only while on", async () => {
    const user = userEvent.setup();
    render(<Owned />);
    const control = screen.getByRole("switch", { name: "Пропускать эндинг" });
    expect(control).not.toHaveClass("switch--on");
    await user.click(control);
    expect(control).toHaveClass("switch--on");
  });
});
