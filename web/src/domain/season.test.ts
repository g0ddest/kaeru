// Vectors from android/src/test/java/app/kaeru/domain/discover/SeasonTest.kt and the season part
// of android/src/test/java/app/kaeru/ui/common/design/FormatTest.kt.
import { describe, expect, it } from "vitest";
import type { Season } from "./season";
import { currentSeason, sameSeason, seasonApiValue, seasonChips, seasonTitle } from "./season";

// A local wall-clock instant, so the season is the viewer's whatever zone runs the tests.
function local(year: number, month: number, day: number, hour = 0, minute = 0, second = 0): number {
  return new Date(year, month - 1, day, hour, minute, second).getTime();
}

const season = (kind: Season["kind"], year: number): Season => ({ kind, year });

describe("currentSeason", () => {
  it("January through March is winter", () => {
    expect(currentSeason(local(2026, 1, 1))).toEqual(season("winter", 2026));
    expect(currentSeason(local(2026, 3, 31, 23, 59, 59))).toEqual(season("winter", 2026));
  });

  it("April through June is spring", () => {
    expect(currentSeason(local(2026, 4, 1))).toEqual(season("spring", 2026));
    expect(currentSeason(local(2026, 6, 30, 12))).toEqual(season("spring", 2026));
  });

  it("July through September is summer", () => {
    expect(currentSeason(local(2026, 7, 1))).toEqual(season("summer", 2026));
    expect(currentSeason(local(2026, 9, 13, 20))).toEqual(season("summer", 2026));
  });

  it("October through December is fall", () => {
    expect(currentSeason(local(2026, 10, 1))).toEqual(season("fall", 2026));
    expect(currentSeason(local(2026, 12, 31, 23))).toEqual(season("fall", 2026));
  });

  it("is the season of the local calendar across the new year", () => {
    expect(currentSeason(local(2026, 12, 31, 23, 59, 59))).toEqual(season("fall", 2026));
    expect(currentSeason(local(2027, 1, 1, 0, 0, 1))).toEqual(season("winter", 2027));
  });
});

describe("seasonChips", () => {
  it("offers the season before, the one now and the one coming", () => {
    expect(seasonChips(local(2026, 8, 1))).toEqual([season("spring", 2026), season("summer", 2026), season("fall", 2026)]);
    expect(seasonChips(local(2026, 5, 1))).toEqual([season("winter", 2026), season("spring", 2026), season("summer", 2026)]);
  });

  it("crosses the new year without losing a season", () => {
    expect(seasonChips(local(2026, 2, 1))).toEqual([season("fall", 2025), season("winter", 2026), season("spring", 2026)]);
    expect(seasonChips(local(2026, 11, 1))).toEqual([season("summer", 2026), season("fall", 2026), season("winter", 2027)]);
  });
});

describe("seasonApiValue", () => {
  it("is what Shikimori calls the season, with fall rather than autumn", () => {
    expect(seasonApiValue(season("winter", 2026))).toBe("winter_2026");
    expect(seasonApiValue(season("spring", 2026))).toBe("spring_2026");
    expect(seasonApiValue(season("summer", 2026))).toBe("summer_2026");
    expect(seasonApiValue(season("fall", 2026))).toBe("fall_2026");
  });
});

describe("seasonTitle", () => {
  it("names the season and the year", () => {
    expect(seasonTitle(season("winter", 2026))).toBe("Зима 2026");
    expect(seasonTitle(season("spring", 2026))).toBe("Весна 2026");
    expect(seasonTitle(season("summer", 2026))).toBe("Лето 2026");
    expect(seasonTitle(season("fall", 2026))).toBe("Осень 2026");
  });

  it("labels the three chips in sentence case with no separator", () => {
    const titles = seasonChips(local(2026, 10, 15)).map(seasonTitle);
    expect(titles).toEqual(["Лето 2026", "Осень 2026", "Зима 2027"]);
    for (const title of titles) {
      expect(title).not.toContain("·");
      expect(title).not.toBe(title.toUpperCase());
    }
  });
});

describe("sameSeason", () => {
  it("compares kind and year", () => {
    expect(sameSeason(season("fall", 2026), season("fall", 2026))).toBe(true);
    expect(sameSeason(season("fall", 2026), season("fall", 2025))).toBe(false);
    expect(sameSeason(season("fall", 2026), season("summer", 2026))).toBe(false);
  });
});
