// Domain shapes every screen shares. Instants and durations are milliseconds.

export type AiringStatus = "ongoing" | "released" | "anons";
export type ListStatus = "watching" | "planned" | "completed" | "rewatching" | "on_hold" | "dropped";

// «Мой список» tabs in Android order (LibraryTabs.kt).
export const LIST_TABS: readonly ListStatus[] = ["watching", "planned", "completed", "rewatching", "on_hold", "dropped"];

// Title-screen status menu, the order both apps use.
export const STATUS_MENU: readonly ListStatus[] = ["watching", "planned", "completed", "on_hold", "dropped", "rewatching"];

/** «Смотреть украдкой» in the status menu and «Мой список», with its hint. */
export const SECRET_LABEL = "Украдкой";
export const SECRET_NOTE = "Не отмечать на Shikimori";

export interface Anime {
  id: number;
  // Russian name, or the original one when Shikimori has no Russian name.
  title: string;
  originalTitle: string;
  posterUrl: string | null;
  backdropUrl: string | null;
  status: AiringStatus;
  // Announced length; 0 means nobody has said (most ongoing shows).
  episodes: number;
  episodesAired: number;
  year: number | null;
  // Shikimori sends "0.0" for unrated titles, so only a value above 0 is a score.
  score: number | null;
  kind: string | null;
  studios: string[];
  description: string | null;
  nextEpisodeAt: number | null;
}

export interface UserRate {
  id: number;
  animeId: number;
  status: ListStatus;
  // Shikimori keeps a count, not a set: episode N is watched when episodes >= N.
  episodes: number;
  updatedAt: number;
}

export interface LibraryEntry {
  anime: Anime;
  /**
   * For a title watched «украдкой» this is not Shikimori's rate: the count is the local one, and the
   * status is «watching», or «completed» once the whole show is behind the viewer.
   */
  rate: UserRate;
  /** «Смотреть украдкой»: nothing about this title is written to Shikimori. */
  secret?: boolean;
}

// Where this browser stopped inside one episode. Lives in localStorage only, never on Shikimori.
export interface EpisodeProgress {
  animeId: number;
  episode: number;
  positionMs: number;
  durationMs: number;
  updatedAt: number;
}

// Episodes that exist to play right now (android domain/model/Anime.kt). Released titles often
// report episodes_aired = 0, so their announced total wins.
export function availableEpisodes(anime: Anime): number {
  switch (anime.status) {
    case "ongoing":
      return anime.episodesAired;
    case "anons":
      return 0;
    case "released":
      return anime.episodes > 0 ? anime.episodes : anime.episodesAired;
  }
}

// Unknown values fall back to "planned", as ListStatus.fromApi does on Android.
export function parseListStatus(raw: string): ListStatus {
  return LIST_TABS.find((status) => status === raw) ?? "planned";
}

// Anything but "ongoing" or "anons" is a finished show (ShikimoriMappers.parseStatus).
export function parseAiringStatus(raw: string): AiringStatus {
  if (raw === "ongoing") return "ongoing";
  if (raw === "anons") return "anons";
  return "released";
}
