// Vectors: Task 6 of docs/superpowers/plans/2026-09-24-kaeru-web-03-player.md (Review Focus 5) and spec
// §5 «пробел — пауза, ←/→ — 10 с, F — полный экран, M — звук, N — следующая серия». The audience types in
// the Russian layout, where the letter keys say «а», «ь», «т»: only the physical key counts.
import { afterEach, describe, expect, it, vi } from "vitest";
import { playerKeyAction, trackFocusOrigin } from "./keys";

afterEach(() => {
  document.body.innerHTML = "";
});

/** A keydown as the browser dispatches it on `target` (the page when left out). */
function press(init: KeyboardEventInit, target: Element = document.body): KeyboardEvent {
  const event = new KeyboardEvent("keydown", { bubbles: true, cancelable: true, ...init });
  let seen: KeyboardEvent | null = null;
  const listen = (e: Event) => {
    seen = e as KeyboardEvent;
  };
  window.addEventListener("keydown", listen);
  target.dispatchEvent(event);
  window.removeEventListener("keydown", listen);
  if (seen === null) throw new Error("the keydown never reached the window");
  return seen;
}

function mount(html: string): HTMLElement {
  document.body.innerHTML = html;
  return document.body;
}

describe("playerKeyAction", () => {
  it.each([
    [{ code: "Space", key: " " }, "toggle"],
    [{ code: "ArrowLeft", key: "ArrowLeft" }, "back10"],
    [{ code: "ArrowRight", key: "ArrowRight" }, "fwd10"],
    [{ code: "KeyF", key: "а" }, "fullscreen"],
    [{ code: "KeyM", key: "ь" }, "mute"],
    [{ code: "KeyN", key: "т" }, "next"],
    [{ code: "KeyF", key: "f" }, "fullscreen"],
    [{ code: "KeyM", key: "M" }, "mute"],
  ] as const)("maps %o to %s", (init, action) => {
    expect(playerKeyAction(press(init))).toBe(action);
  });

  it("goes by the physical key, not the letter the layout types", () => {
    // Dvorak's F sits where QWERTY has Y: the letter is f, the key is not.
    expect(playerKeyAction(press({ code: "KeyY", key: "f" }))).toBeNull();
    expect(playerKeyAction(press({ code: "KeyA", key: "ф" }))).toBeNull();
    expect(playerKeyAction(press({ code: "Enter", key: "Enter" }))).toBeNull();
  });

  it.each([
    ["Ctrl", { ctrlKey: true }],
    ["Cmd", { metaKey: true }],
    ["Alt", { altKey: true }],
  ])("leaves %s combinations to the browser", (_name, modifier) => {
    expect(playerKeyAction(press({ code: "KeyF", key: "а", ...modifier }))).toBeNull();
    expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight", ...modifier }))).toBeNull();
  });

  it("ignores a key while an input method is composing", () => {
    expect(playerKeyAction(press({ code: "KeyN", key: "т", isComposing: true }))).toBeNull();
  });

  it("ignores a key something else already handled", () => {
    const body = mount('<div id="menu-trigger"></div>');
    body.querySelector("#menu-trigger")?.addEventListener("keydown", (e) => e.preventDefault());
    const event = press({ code: "ArrowRight", key: "ArrowRight" }, body.querySelector("#menu-trigger") as Element);
    expect(playerKeyAction(event)).toBeNull();
  });

  it.each([
    ["a text input", '<input id="t" type="text">'],
    ["a textarea", '<textarea id="t"></textarea>'],
    ["a select", '<select id="t"><option>1</option></select>'],
    ["an editable block", '<div contenteditable="true"><span id="t">чат</span></div>'],
  ])("ignores keys typed into %s", (_name, html) => {
    const target = mount(html).querySelector("#t") as Element;
    expect(playerKeyAction(press({ code: "KeyN", key: "т" }, target))).toBeNull();
    expect(playerKeyAction(press({ code: "Space", key: " " }, target))).toBeNull();
    expect(playerKeyAction(press({ code: "ArrowLeft", key: "ArrowLeft" }, target))).toBeNull();
  });

  it("ignores keys inside an open menu or a dialog", () => {
    const body = mount(
      '<ul role="menu"><li role="none"><button id="item" role="menuitemradio">720p</button></li></ul>' +
        '<div role="dialog" aria-modal="true"><button id="yes">Да</button></div>',
    );
    for (const id of ["#item", "#yes"]) {
      const target = body.querySelector(id) as Element;
      expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight" }, target))).toBeNull();
      expect(playerKeyAction(press({ code: "KeyM", key: "ь" }, target))).toBeNull();
    }
  });

  it("lets Space press a button or link the keyboard focused, while the other keys still work there", () => {
    const body = mount('<button id="b">Отмена</button><a id="a" href="/anime/1">К списку серий</a><div id="r" role="button">x</div>');
    const keyboard = () => true;
    for (const id of ["#b", "#a", "#r"]) {
      const target = body.querySelector(id) as Element;
      expect(playerKeyAction(press({ code: "Space", key: " " }, target), keyboard)).toBeNull();
      expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight" }, target), keyboard)).toBe("fwd10");
      expect(playerKeyAction(press({ code: "KeyF", key: "а" }, target), keyboard)).toBe("fullscreen");
    }
  });

  it("pauses on Space over a button or link a pointer or a script left focused, rather than pressing it", () => {
    const body = mount(
      '<button id="b">Вперёд на 10 секунд</button><a id="a" href="/anime/1">К списку серий</a><div id="r" role="button"><span id="s">x</span></div>',
    );
    const asked: Element[] = [];
    const pointer = (element: Element) => {
      asked.push(element);
      return false;
    };
    for (const id of ["#b", "#a", "#s"]) {
      expect(playerKeyAction(press({ code: "Space", key: " " }, body.querySelector(id) as Element), pointer)).toBe("toggle");
    }
    // The control itself is asked, not whatever inside it the key landed on.
    expect(asked.map((element) => element.id)).toEqual(["b", "a", "r"]);
  });

  it("takes Space on a focused control by :focus-visible when nothing else is asked", () => {
    const body = mount('<button id="b">Вперёд на 10 секунд</button>');
    const button = body.querySelector("#b") as HTMLButtonElement;
    const matches = Element.prototype.matches;
    let ring = true;
    const spy = vi.spyOn(Element.prototype, "matches").mockImplementation(function (this: Element, selector: string) {
      return selector === ":focus-visible" ? ring : matches.call(this, selector);
    });
    try {
      button.focus();
      expect(playerKeyAction(press({ code: "Space", key: " " }, button))).toBeNull();
      ring = false;
      expect(playerKeyAction(press({ code: "Space", key: " " }, button))).toBe("toggle");
    } finally {
      spy.mockRestore();
    }
  });

  it("remembers whether the keyboard put the focus on a control, not what the ring says afterwards", () => {
    const body = mount('<button id="a">Вперёд на 10 секунд</button><button id="b">Смотреть сейчас</button>');
    const [a, b] = ["#a", "#b"].map((id) => body.querySelector(id) as HTMLButtonElement);
    const matches = Element.prototype.matches;
    let ring = false;
    const spy = vi.spyOn(Element.prototype, "matches").mockImplementation(function (this: Element, selector: string) {
      return selector === ":focus-visible" ? ring && this === document.activeElement : matches.call(this, selector);
    });
    const origin = trackFocusOrigin();
    try {
      // A click: no ring as the focus arrives. Chrome turns it on at the first key pressed there.
      a.focus();
      ring = true;
      expect(origin.keyboardFocused(a)).toBe(false);
      // Tab: the ring is there as the focus arrives.
      b.focus();
      expect(origin.keyboardFocused(b)).toBe(true);
      // Not seen arriving (focused before the player opened): the ring as it is now.
      expect(origin.keyboardFocused(a)).toBe(false);
      origin.stop();
      a.focus();
      ring = false;
      expect(origin.keyboardFocused(a)).toBe(false);
    } finally {
      origin.stop();
      spy.mockRestore();
    }
  });

  it("hands every player key through the seek slider a click left focused, its arrows included", () => {
    const slider = mount('<input id="t" type="range" min="0" max="1440" step="1">').querySelector("#t") as Element;
    expect(playerKeyAction(press({ code: "Space", key: " " }, slider))).toBe("toggle");
    expect(playerKeyAction(press({ code: "KeyF", key: "а" }, slider))).toBe("fullscreen");
    expect(playerKeyAction(press({ code: "KeyM", key: "ь" }, slider))).toBe("mute");
    expect(playerKeyAction(press({ code: "KeyN", key: "т" }, slider))).toBe("next");
    expect(playerKeyAction(press({ code: "ArrowLeft", key: "ArrowLeft" }, slider))).toBe("back10");
    expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight" }, slider))).toBe("fwd10");
    // Browser shortcuts stay the browser's there too.
    expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight", altKey: true }, slider))).toBeNull();
  });

  it("repeats only the arrows while a key is held", () => {
    expect(playerKeyAction(press({ code: "ArrowLeft", key: "ArrowLeft", repeat: true }))).toBe("back10");
    expect(playerKeyAction(press({ code: "ArrowRight", key: "ArrowRight", repeat: true }))).toBe("fwd10");
    expect(playerKeyAction(press({ code: "Space", key: " ", repeat: true }))).toBeNull();
    expect(playerKeyAction(press({ code: "KeyF", key: "а", repeat: true }))).toBeNull();
    expect(playerKeyAction(press({ code: "KeyM", key: "ь", repeat: true }))).toBeNull();
    expect(playerKeyAction(press({ code: "KeyN", key: "т", repeat: true }))).toBeNull();
  });
});
