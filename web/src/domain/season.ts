// Broadcast seasons, as android domain/discover/Season.kt computes them.

export type SeasonKind = "winter" | "spring" | "summer" | "fall";

export interface Season {
  kind: SeasonKind;
  year: number;
}

// Quarter of the local date: January starts winter, April spring, July summer, October fall.
export function currentSeason(now: number): Season {
  const date = new Date(now);
  const month = date.getMonth();
  const kind: SeasonKind = month < 3 ? "winter" : month < 6 ? "spring" : month < 9 ? "summer" : "fall";
  return { kind, year: date.getFullYear() };
}

function previous(season: Season): Season {
  switch (season.kind) {
    case "winter":
      return { kind: "fall", year: season.year - 1 };
    case "spring":
      return { kind: "winter", year: season.year };
    case "summer":
      return { kind: "spring", year: season.year };
    case "fall":
      return { kind: "summer", year: season.year };
  }
}

function next(season: Season): Season {
  switch (season.kind) {
    case "winter":
      return { kind: "spring", year: season.year };
    case "spring":
      return { kind: "summer", year: season.year };
    case "summer":
      return { kind: "fall", year: season.year };
    case "fall":
      return { kind: "winter", year: season.year + 1 };
  }
}

// The season that just ended, the one airing and the one coming, left to right.
export function seasonChips(now: number): [Season, Season, Season] {
  const current = currentSeason(now);
  return [previous(current), current, next(current)];
}

// Shikimori's `season` filter value. "autumn_2026" gets HTTP 422, so the fourth one is "fall".
export function seasonApiValue(s: Season): string {
  return `${s.kind}_${s.year}`;
}

function seasonName(kind: SeasonKind): string {
  switch (kind) {
    case "winter":
      return "Зима";
    case "spring":
      return "Весна";
    case "summer":
      return "Лето";
    case "fall":
      return "Осень";
  }
}

export function seasonTitle(s: Season): string {
  return `${seasonName(s.kind)} ${s.year}`;
}

export function sameSeason(a: Season, b: Season): boolean {
  return a.kind === b.kind && a.year === b.year;
}
