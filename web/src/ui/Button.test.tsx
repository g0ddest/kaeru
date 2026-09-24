import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { FormEvent } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { DestructiveButton, IconButton, PrimaryButton, SecondaryButton, TextAction } from "./Button";
import { IconBack, IconPlay } from "./icons";

describe("buttons", () => {
  it("are real buttons that never submit a form by accident", async () => {
    const onClick = vi.fn();
    const onSubmit = vi.fn((event: FormEvent) => event.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <PrimaryButton onClick={onClick}>Смотреть 1 серию</PrimaryButton>
      </form>,
    );
    const button = screen.getByRole("button", { name: "Смотреть 1 серию" });
    expect(button).toHaveAttribute("type", "button");
    await userEvent.setup().click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("ignore presses while disabled", async () => {
    const onClick = vi.fn();
    render(
      <PrimaryButton disabled onClick={onClick}>
        Ждём 9 серию
      </PrimaryButton>,
    );
    const button = screen.getByRole("button", { name: "Ждём 9 серию" });
    expect(button).toBeDisabled();
    await userEvent.setup().click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("become router links when they navigate", async () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<SecondaryButton to="/anime/5">Подробнее</SecondaryButton>} />
          <Route path="/anime/:id" element={<p>Страница тайтла</p>} />
        </Routes>
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "Подробнее" });
    expect(link).toHaveAttribute("href", "/anime/5");
    await userEvent.setup().click(link);
    expect(screen.getByText("Страница тайтла")).toBeInTheDocument();
  });

  it("keep the icon out of the accessible name", () => {
    render(<PrimaryButton icon={<IconPlay />}>Продолжить с 7:26</PrimaryButton>);
    const button = screen.getByRole("button", { name: "Продолжить с 7:26" });
    expect(button.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("carry their variant for styling", () => {
    render(
      <>
        <PrimaryButton>Первая</PrimaryButton>
        <SecondaryButton>Вторая</SecondaryButton>
        <TextAction>Третья</TextAction>
        <DestructiveButton>Четвёртая</DestructiveButton>
        <PrimaryButton fullWidth>Пятая</PrimaryButton>
        <SecondaryButton compact>Шестая</SecondaryButton>
      </>,
    );
    expect(screen.getByRole("button", { name: "Первая" })).toHaveClass("btn", "btn--primary");
    expect(screen.getByRole("button", { name: "Вторая" })).toHaveClass("btn", "btn--secondary");
    expect(screen.getByRole("button", { name: "Третья" })).toHaveClass("btn", "btn--text");
    expect(screen.getByRole("button", { name: "Четвёртая" })).toHaveClass("btn", "btn--destructive");
    expect(screen.getByRole("button", { name: "Пятая" })).toHaveClass("btn--full");
    expect(screen.getByRole("button", { name: "Шестая" })).toHaveClass("btn--compact");
  });

  it("let a text action show a chevron", () => {
    render(
      <MemoryRouter>
        <TextAction to="/list" chevron>
          Всё
        </TextAction>
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "Всё" });
    expect(link.querySelectorAll("svg")).toHaveLength(1);
  });

  it("name an icon button by its label", async () => {
    const onClick = vi.fn();
    render(<IconButton label="Назад" icon={<IconBack />} onClick={onClick} overArt />);
    const button = screen.getByRole("button", { name: "Назад" });
    expect(button).toHaveClass("icon-button", "icon-button--over-art");
    await userEvent.setup().click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
