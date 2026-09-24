import { availableEpisodes, type Anime, type EpisodeProgress, type LibraryEntry } from "../domain/models";
import { episodeFraction, isStarted, progressAt } from "../domain/progress";

/** Rows shown at first and added per «Показать ещё». */
export const EPISODE_PAGE = 60;

export interface EpisodeRow {
  number: number;
  /** Shikimori's count has reached it. */
  watched: boolean;
  /** There is something to play. */
  aired: boolean;
  /** Where this browser stopped, or null when there is nothing worth drawing. */
  fraction: number | null;
  /** The stop behind `fraction`; 0 whenever `fraction` is null. */
  positionMs: number;
}

/**
 * Every episode the title announced, marked with what is behind the viewer, what they are in the
 * middle of and what has not arrived yet (Android EpisodeGrid.episodeCells).
 */
export function episodeRows(
  anime: Anime,
  entry: LibraryEntry | null,
  progress: readonly EpisodeProgress[],
  threshold: number,
): EpisodeRow[] {
  const own = progress.filter((row) => row.animeId === anime.id);
  // A title in no list borrows an empty rate, so the rules stay the ones the watch button uses.
  const effective: LibraryEntry = {
    anime,
    rate: entry?.rate ?? { id: 0, animeId: anime.id, status: "planned", episodes: 0, updatedAt: 0 },
  };
  const seen = entry?.rate.episodes ?? 0;
  // Only a really started episode stretches the list past the catalogue; a mis-tap must not.
  const started = own.filter(isStarted).reduce((highest, row) => Math.max(highest, row.episode), 0);
  const playable = Math.max(availableEpisodes(anime), seen, started);
  const total = Math.max(anime.episodes, playable);
  const rows: EpisodeRow[] = [];
  for (let number = 1; number <= total; number += 1) {
    const fraction = episodeFraction(effective, own, number, threshold);
    rows.push({
      number,
      watched: number <= seen,
      aired: number <= playable,
      fraction,
      positionMs: fraction === null ? 0 : (progressAt(own, number)?.positionMs ?? 0),
    });
  }
  return rows;
}
