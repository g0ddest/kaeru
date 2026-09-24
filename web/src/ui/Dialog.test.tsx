import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Dialog } from "./Dialog";

// Copy: android/src/main/java/app/kaeru/ui/mobile/settings/SettingsScreen.kt:54-59 (web-map 3).
function Harness({ onConfirm = () => undefined }: { onConfirm?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Выйти из аккаунта
      </button>
      <Dialog
        open={open}
        title="Выйти из аккаунта?"
        text="Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
        confirmLabel="Выйти"
        cancelLabel="Отмена"
        destructive
        onConfirm={() => {
          onConfirm();
          setOpen(false);
        }}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}

describe("Dialog", () => {
  it("renders nothing while closed", () => {
    render(<Harness />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens as a named, described modal with focus on the safe choice", async () => {
    render(<Harness />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const dialog = screen.getByRole("dialog", { name: "Выйти из аккаунта?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("Список и прогресс останутся на Shikimori, локальный кэш будет очищен");
    expect(within(dialog).getByRole("button", { name: "Отмена" })).toHaveFocus();
    expect(within(dialog).getByRole("button", { name: "Выйти" })).toHaveClass("btn--destructive");
  });

  it("keeps Tab inside the dialog", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    const cancel = screen.getByRole("button", { name: "Отмена" });
    const confirm = screen.getByRole("button", { name: "Выйти" });
    await user.tab();
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();
  });

  it("closes on Escape and gives focus back to the opener", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole("button", { name: "Выйти из аккаунта" });
    await user.click(opener);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(opener).toHaveFocus();
  });

  it("confirms once", async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(<Harness onConfirm={onConfirm} />);
    await user.click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    await user.click(screen.getByRole("button", { name: "Выйти" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("closes when the backdrop is pressed", async () => {
    render(<Harness />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Выйти из аккаунта" }));
    fireEvent.mouseDown(screen.getByRole("dialog").parentElement as HTMLElement);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("uses the primary button for a confirm that is not destructive", () => {
    // Decision 6: the completion dialog.
    render(
      <Dialog
        open
        title="Перевести аниме в завершённые?"
        confirmLabel="Завершить просмотр"
        cancelLabel="Позже"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
    );
    const dialog = screen.getByRole("dialog", { name: "Перевести аниме в завершённые?" });
    expect(dialog).not.toHaveAttribute("aria-describedby");
    expect(within(dialog).getByRole("button", { name: "Завершить просмотр" })).toHaveClass("btn--primary");
    expect(within(dialog).getByRole("button", { name: "Позже" })).toHaveFocus();
  });
});
