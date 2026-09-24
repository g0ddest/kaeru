// Vectors from android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt unless a test says
// otherwise; facts-line vectors follow ios/Features/DetailView.swift (facts, kindTitle).
import { describe, expect, it } from "vitest";
import type { Anime } from "./models";
import {
  airingLabel,
  cleanDescription,
  episodeBadge,
  factsLine,
  formatTime,
  kindLabel,
  plural,
  pluralEpisodes,
  pluralEpisodesAccusative,
  relativeDay,
  remainingLine,
  statusLabel,
} from "./format";

// A local wall-clock instant: the rules count days in the viewer's zone, whatever zone runs the tests.
function at(year: number, month: number, day: number, hour: number, minute: number): number {
  return new Date(year, month - 1, day, hour, minute).getTime();
}

// 12 April 2026, 21:30 — FormatTest's ordinary evening on the sofa.
const now = at(2026, 4, 12, 21, 30);

function anime(overrides: Partial<Anime> = {}): Anime {
  return {
    id: 1535,
    title: "Тетрадь смерти",
    originalTitle: "Death Note",
    posterUrl: null,
    backdropUrl: null,
    status: "released",
    episodes: 37,
    episodesAired: 0,
    year: 2006,
    score: 8.62,
    kind: "tv",
    studios: ["Madhouse"],
    description: null,
    nextEpisodeAt: null,
    ...overrides,
  };
}

describe("plural", () => {
  it("picks one, few or many with the 11–14 exception", () => {
    expect(plural(1, "день", "дня", "дней")).toBe("день");
    expect(plural(2, "день", "дня", "дней")).toBe("дня");
    expect(plural(4, "день", "дня", "дней")).toBe("дня");
    expect(plural(5, "день", "дня", "дней")).toBe("дней");
    expect(plural(0, "день", "дня", "дней")).toBe("дней");
    expect(plural(11, "день", "дня", "дней")).toBe("дней");
    expect(plural(14, "день", "дня", "дней")).toBe("дней");
    expect(plural(21, "день", "дня", "дней")).toBe("день");
    expect(plural(111, "день", "дня", "дней")).toBe("дней");
    expect(plural(-3, "день", "дня", "дней")).toBe("дня");
  });
});

describe("pluralEpisodes", () => {
  it("follows the Russian count", () => {
    expect(pluralEpisodes(1)).toBe("1 серия");
    expect(pluralEpisodes(3)).toBe("3 серии");
    expect(pluralEpisodes(12)).toBe("12 серий");
    expect(pluralEpisodes(21)).toBe("21 серия");
    expect(pluralEpisodes(24)).toBe("24 серии");
    expect(pluralEpisodes(11)).toBe("11 серий");
  });
});

describe("pluralEpisodesAccusative", () => {
  it("puts the count in the accusative", () => {
    expect(pluralEpisodesAccusative(1)).toBe("1 серию");
    expect(pluralEpisodesAccusative(2)).toBe("2 серии");
    expect(pluralEpisodesAccusative(4)).toBe("4 серии");
    expect(pluralEpisodesAccusative(5)).toBe("5 серий");
    expect(pluralEpisodesAccusative(21)).toBe("21 серию");
    expect(pluralEpisodesAccusative(22)).toBe("22 серии");
    expect(pluralEpisodesAccusative(60)).toBe("60 серий");
  });

  it("keeps the eleven to fourteen exception", () => {
    expect(pluralEpisodesAccusative(11)).toBe("11 серий");
    expect(pluralEpisodesAccusative(12)).toBe("12 серий");
    expect(pluralEpisodesAccusative(14)).toBe("14 серий");
    expect(pluralEpisodesAccusative(111)).toBe("111 серий");
  });

  it("differs from the nominative exactly where Russian says it does", () => {
    expect(pluralEpisodes(21)).toBe("21 серия");
    expect(pluralEpisodesAccusative(21)).toBe("21 серию");
    for (let n = 2; n <= 10; n++) expect(pluralEpisodesAccusative(n)).toBe(pluralEpisodes(n));
  });
});

describe("formatTime", () => {
  it("drops the hour until there is one", () => {
    expect(formatTime(0)).toBe("0:00");
    expect(formatTime(860_000)).toBe("14:20");
    expect(formatTime(3_754_000)).toBe("1:02:34");
  });

  it("floors to whole seconds and never prints a negative or NaN time", () => {
    expect(formatTime(59_999)).toBe("0:59");
    expect(formatTime(-5_000)).toBe("0:00");
    expect(formatTime(Number.NaN)).toBe("0:00");
  });
});

describe("remainingLine", () => {
  it("has nothing to say about an unknown or finished episode", () => {
    expect(remainingLine(600_000, 0)).toBeNull();
    expect(remainingLine(1_440_000, 1_440_000)).toBeNull();
    expect(remainingLine(2_000_000, 1_440_000)).toBeNull();
  });

  it("names the last seconds with a phrase, not a zero", () => {
    expect(remainingLine(1_410_000, 1_440_000)).toBe("осталось меньше минуты");
  });

  it("floors minutes so the number is never a promise", () => {
    expect(remainingLine(600_000, 1_440_000)).toBe("осталось 14 мин");
    expect(remainingLine(540_000, 1_440_000 - 41_000)).toBe("осталось 14 мин");
  });

  it("reads an hour or more in hours and minutes", () => {
    expect(remainingLine(0, 4_800_000)).toBe("осталось 1 ч 20 мин");
    expect(remainingLine(0, 7_200_000)).toBe("осталось 2 ч");
  });
});

describe("relativeDay", () => {
  it("names today and tomorrow by the calendar, not by 24-hour buckets", () => {
    expect(relativeDay(at(2026, 4, 12, 23, 45), now)).toBe("сегодня");
    expect(relativeDay(at(2026, 4, 13, 1, 15), now)).toBe("завтра");
  });

  it("counts days ahead with the Russian plural", () => {
    expect(relativeDay(at(2026, 4, 14, 12, 0), now)).toBe("через 2 дня");
    expect(relativeDay(at(2026, 4, 15, 12, 0), now)).toBe("через 3 дня");
    expect(relativeDay(at(2026, 4, 17, 12, 0), now)).toBe("через 5 дней");
    expect(relativeDay(at(2026, 4, 23, 12, 0), now)).toBe("через 11 дней");
    expect(relativeDay(at(2026, 5, 3, 12, 0), now)).toBe("через 21 день");
  });

  it("counts the past backwards the same way", () => {
    expect(relativeDay(at(2026, 4, 11, 3, 0), now)).toBe("вчера");
    expect(relativeDay(at(2026, 4, 9, 12, 0), now)).toBe("3 дня назад");
    expect(relativeDay(at(2026, 4, 5, 12, 0), now)).toBe("7 дней назад");
  });
});

describe("labels", () => {
  it("names every list status, completed as «Завершено»", () => {
    expect(statusLabel("watching")).toBe("Смотрю");
    expect(statusLabel("planned")).toBe("В планах");
    expect(statusLabel("completed")).toBe("Завершено");
    expect(statusLabel("rewatching")).toBe("Пересматриваю");
    expect(statusLabel("on_hold")).toBe("Отложено");
    expect(statusLabel("dropped")).toBe("Брошено");
  });

  it("names every airing status", () => {
    expect(airingLabel("ongoing")).toBe("Онгоинг");
    expect(airingLabel("released")).toBe("Вышло");
    expect(airingLabel("anons")).toBe("Анонс");
  });

  it("names the kinds iOS knows and upper-cases the rest", () => {
    expect(kindLabel("tv")).toBe("Сериал");
    expect(kindLabel("movie")).toBe("Фильм");
    expect(kindLabel("ova")).toBe("OVA");
    expect(kindLabel("ona")).toBe("ONA");
    expect(kindLabel("special")).toBe("Спецвыпуск");
    expect(kindLabel("tv_special")).toBe("Спецвыпуск");
    expect(kindLabel("music")).toBe("Музыкальное видео");
    expect(kindLabel("cm")).toBe("CM");
    expect(kindLabel("pv")).toBe("PV");
  });

  it("badges an episode by its number with the noun unchanged", () => {
    // android ui/common/home/HomeRows.kt episodeBadge
    expect(episodeBadge(7)).toBe("7 серия");
    expect(episodeBadge(21)).toBe("21 серия");
    expect(episodeBadge(22)).toBe("22 серия");
  });
});

describe("factsLine", () => {
  it("joins the facts with a middle dot in the iOS order", () => {
    expect(factsLine(anime())).toBe("Вышло · 2006 · 37 эп. · ★ 8.62 · Сериал · Madhouse");
  });

  it("adds how many aired for an ongoing show, after the score", () => {
    const ongoing = anime({
      status: "ongoing",
      year: 2026,
      episodes: 12,
      episodesAired: 8,
      score: 8.1,
      studios: ["MAPPA", "Studio Pierrot"],
    });
    expect(factsLine(ongoing)).toBe("Онгоинг · 2026 · 12 эп. · ★ 8.1 · вышло 8 · Сериал · MAPPA, Studio Pierrot");
  });

  it("hides a score of zero or none, and keeps the decimal of a whole score", () => {
    expect(factsLine(anime({ score: 0 }))).toBe("Вышло · 2006 · 37 эп. · Сериал · Madhouse");
    expect(factsLine(anime({ score: null }))).toBe("Вышло · 2006 · 37 эп. · Сериал · Madhouse");
    expect(factsLine(anime({ score: 9 }))).toBe("Вышло · 2006 · 37 эп. · ★ 9.0 · Сериал · Madhouse");
  });

  it("drops the facts nobody knows", () => {
    const bare = anime({ status: "ongoing", year: null, episodes: 0, episodesAired: 3, score: null, kind: null, studios: [] });
    expect(factsLine(bare)).toBe("Онгоинг · вышло 3");
    const announced = anime({ status: "anons", year: 2027, episodes: 0, score: null, kind: "movie", studios: ["", " "] });
    expect(factsLine(announced)).toBe("Анонс · 2027 · Фильм");
  });
});

describe("cleanDescription", () => {
  it("strips BBCode keeping the inner text and unwraps wiki links", () => {
    // The live description of 1535 carries [[Синигами]] and [character=…] tags.
    const raw = "Лайт находит тетрадь [[Синигами]] [character=80]Рюка[/character]. [spoiler=спойлер]Тайна[/spoiler]";
    expect(cleanDescription(raw)).toBe("Лайт находит тетрадь Синигами Рюка. Тайна");
  });

  it("strips HTML and then decodes entities, so escaped text survives as text", () => {
    const raw = "Цитата:<br> &quot;Я — справедливость&quot; &amp; &lt;не только&gt; &#39;это&#39; &amp;lt;";
    expect(cleanDescription(raw)).toBe("Цитата: \"Я — справедливость\" & <не только> 'это' &lt;");
  });

  it("keeps line breaks inside and trims the ends", () => {
    expect(cleanDescription("  Первый абзац.\n\nВторой [b]абзац[/b].  ")).toBe("Первый абзац.\n\nВторой абзац.");
  });

  it("returns null for nothing left to show", () => {
    expect(cleanDescription(null)).toBeNull();
    expect(cleanDescription("   ")).toBeNull();
    expect(cleanDescription("[b][/b]")).toBeNull();
  });
});
