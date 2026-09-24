// Vectors: android domain/playback/TranslationRanker.kt, player/EpisodeQueue.kt, domain/playback/SkipMarks.kt
// (with the AniSkip answers of SkipRulesTest.kt) and ui/common/player/PlayerViewModel.kt resumeFrom.
import { describe, expect, it } from "vitest";
import type { AiringStatus, Anime, EpisodeProgress } from "../domain/models";
import type { Translation } from "./kodik";
import * as rules from "./rules";
import {
  DEFAULT_STUDIOS,
  NO_MARKS,
  countdown,
  endingDue,
  hasNextEpisode,
  lacksEpisode,
  plausibleMarks,
  rankTranslations,
  resumeFrom,
  shouldAutoSkip,
  skipOffer,
  startQuality,
} from "./rules";
import type { SkipMarks } from "./rules";

function track(id: number, title: string, rest: Partial<Translation> = {}): Translation {
  return { id, title, type: "voice", episodesCount: null, ...rest };
}

const noHistory = { remembered: null, usage: new Map<number, number>() };

function ids(tracks: readonly Translation[]): number[] {
  return tracks.map((t) => t.id);
}

describe("rankTranslations", () => {
  it("ships Android's studio list in its order", () => {
    expect(DEFAULT_STUDIOS).toEqual([
      "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza", "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
    ]);
  });

  it("puts the dub this title was last played with ahead of a default studio", () => {
    const tracks = [track(610, "AniLibria.TV"), track(1978, "Studio Band")];

    expect(ids(rankTranslations(tracks, { remembered: 1978, usage: new Map() }))).toEqual([1978, 610]);
  });

  it("puts a dub the viewer settled on for other titles ahead of a default studio", () => {
    const tracks = [track(610, "AniLibria.TV"), track(1978, "Studio Band")];

    expect(ids(rankTranslations(tracks, { remembered: null, usage: new Map([[1978, 1]]) }))).toEqual([1978, 610]);
  });

  it("counts usage, most first", () => {
    const tracks = [track(1, "Studio Band"), track(2, "Persona99"), track(3, "Ancord")];
    const usage = new Map([[1, 1], [2, 3], [3, 2]]);

    expect(ids(rankTranslations(tracks, { remembered: null, usage }))).toEqual([2, 3, 1]);
  });

  it("finds a default studio anywhere in the title, whatever the case", () => {
    const tracks = [track(1, "Studio Band"), track(610, "Anilibria.TV")];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([610, 1]);
  });

  it("prefers the earlier default studio and puts unknown studios after every known one", () => {
    const tracks = [track(1, "Studio Band"), track(2, "SHIZA Project"), track(3, "AniDUB"), track(4, "AniLibria")];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([4, 3, 2, 1]);
  });

  it("puts a dub before subtitles", () => {
    const tracks = [track(1, "Studio Band", { type: "subtitles" }), track(2, "Persona99")];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([2, 1]);
  });

  it("puts the dub carrying more episodes first, an unknown count as none", () => {
    const tracks = [
      track(1, "Studio Band", { episodesCount: 5 }),
      track(2, "Persona99", { episodesCount: null }),
      track(3, "Ancord", { episodesCount: 12 }),
    ];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([3, 1, 2]);
  });

  it("does not rank subtitles by their episode count", () => {
    const tracks = [
      track(1, "Studio Band", { type: "subtitles", episodesCount: 5 }),
      track(2, "Persona99", { type: "subtitles", episodesCount: 12 }),
    ];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([1, 2]);
  });

  it("keeps Kodik's order for tracks the rules cannot tell apart, and leaves the input alone", () => {
    const tracks = [track(3, "Studio Band"), track(1, "Persona99"), track(2, "Ancord")];

    expect(ids(rankTranslations(tracks, noHistory))).toEqual([3, 1, 2]);
    expect(ids(rankTranslations([track(2, "Ancord"), track(1, "Persona99")], noHistory))).toEqual([2, 1]);
    rankTranslations(tracks, { remembered: 2, usage: new Map() });
    expect(ids(tracks)).toEqual([3, 1, 2]);
  });
});

describe("lacksEpisode", () => {
  it("trusts only a positive count that stops short of the episode", () => {
    expect(lacksEpisode(track(1, "Studio Band", { episodesCount: null }), 7)).toBe(false);
    expect(lacksEpisode(track(1, "Studio Band", { episodesCount: 0 }), 7)).toBe(false);
    expect(lacksEpisode(track(1, "Studio Band", { episodesCount: 6 }), 7)).toBe(true);
    expect(lacksEpisode(track(1, "Studio Band", { episodesCount: 7 }), 7)).toBe(false);
  });
});

describe("timings", () => {
  it("are Android's, in milliseconds", () => {
    expect({
      SEEK_STEP_MS: rules.SEEK_STEP_MS,
      JUMP_MS: rules.JUMP_MS,
      SAVE_EVERY_MS: rules.SAVE_EVERY_MS,
      ENDING_ZONE_MS: rules.ENDING_ZONE_MS,
      COUNTDOWN_S: rules.COUNTDOWN_S,
      SKIP_OFFER_MS: rules.SKIP_OFFER_MS,
      OPENING_WITHIN_MS: rules.OPENING_WITHIN_MS,
      ENDING_WITHIN_MS: rules.ENDING_WITHIN_MS,
      INTERVAL_MIN_MS: rules.INTERVAL_MIN_MS,
      INTERVAL_MAX_MS: rules.INTERVAL_MAX_MS,
      SEEK_SETTLE_MS: rules.SEEK_SETTLE_MS,
      CONTROLS_HIDE_MS: rules.CONTROLS_HIDE_MS,
    }).toEqual({
      SEEK_STEP_MS: 10_000,
      JUMP_MS: 85_000,
      SAVE_EVERY_MS: 5_000,
      ENDING_ZONE_MS: 30_000,
      COUNTDOWN_S: 10,
      SKIP_OFFER_MS: 10_000,
      OPENING_WITHIN_MS: 300_000,
      ENDING_WITHIN_MS: 180_000,
      INTERVAL_MIN_MS: 60_000,
      INTERVAL_MAX_MS: 150_000,
      SEEK_SETTLE_MS: 1_000,
      CONTROLS_HIDE_MS: 3_000,
    });
  });
});

const EPISODE_MS = 1_440_000; // 24 minutes

function saved(positionMs: number, durationMs = EPISODE_MS): EpisodeProgress {
  return { animeId: 1535, episode: 3, positionMs, durationMs, updatedAt: 1_000 };
}

describe("resumeFrom", () => {
  it("starts an episode this browser never played from the top", () => {
    expect(resumeFrom(undefined, 0.9)).toBe(0);
  });

  it("starts a mis-tap over: under a minute and under 2 % is no place to resume", () => {
    expect(resumeFrom(saved(20_000), 0.9)).toBe(0);
    expect(resumeFrom(saved(59_000, 3_600_000), 0.9)).toBe(0);
  });

  it("starts an episode watched to the threshold over", () => {
    expect(resumeFrom(saved(EPISODE_MS * 0.9), 0.9)).toBe(0);
    expect(resumeFrom(saved(EPISODE_MS * 0.85), 0.8)).toBe(0);
  });

  it("picks up where the viewer stopped", () => {
    expect(resumeFrom(saved(860_000), 0.9)).toBe(860_000);
    // Past 2 % of a normal episode is watching, even short of a minute.
    expect(resumeFrom(saved(59_000), 0.9)).toBe(59_000);
  });
});

describe("startQuality", () => {
  it("uses the viewer's quality when this stream has it", () => {
    expect(startQuality([1080, 720, 480], 720)).toBe(720);
  });

  it("falls back to the best on offer", () => {
    expect(startQuality([480, 720, 360], 1080)).toBe(720);
    expect(startQuality([720, 480], null)).toBe(720);
  });
});

function anime(status: AiringStatus, episodes: number, episodesAired: number): Anime {
  return {
    id: 1535,
    title: "Тетрадь смерти",
    originalTitle: "Death Note",
    posterUrl: null,
    backdropUrl: null,
    status,
    episodes,
    episodesAired,
    year: 2006,
    score: null,
    kind: "tv",
    studios: [],
    description: null,
    nextEpisodeAt: null,
  };
}

describe("hasNextEpisode", () => {
  it("offers only what an ongoing show has aired", () => {
    expect(hasNextEpisode(anime("ongoing", 24, 8), 7)).toBe(true);
    expect(hasNextEpisode(anime("ongoing", 24, 8), 8)).toBe(false);
  });

  it("offers nothing for an announcement", () => {
    expect(hasNextEpisode(anime("anons", 12, 5), 1)).toBe(false);
  });

  it("counts a released show by its announced length", () => {
    expect(hasNextEpisode(anime("released", 37, 0), 36)).toBe(true);
    expect(hasNextEpisode(anime("released", 37, 0), 37)).toBe(false);
  });
});

describe("endingDue", () => {
  it("opens in the last 30 s or once the episode ended", () => {
    expect(endingDue(EPISODE_MS - 30_001, EPISODE_MS, false)).toBe(false);
    expect(endingDue(EPISODE_MS - 30_000, EPISODE_MS, false)).toBe(true);
    expect(endingDue(0, 0, true)).toBe(true);
  });

  it("needs a known length", () => {
    expect(endingDue(0, 0, false)).toBe(false);
  });
});

describe("countdown", () => {
  const at = (remainingMs: number, rest: Partial<Parameters<typeof countdown>[0]> = {}) =>
    countdown({
      positionMs: EPISODE_MS - remainingMs,
      durationMs: EPISODE_MS,
      ended: false,
      autoplay: true,
      cancelled: false,
      hasNext: true,
      ...rest,
    });

  it("runs the last ten seconds, rounding up", () => {
    expect(at(11_000)).toBeNull();
    expect(at(10_000)).toBe(10);
    expect(at(9_200)).toBe(10);
    expect(at(400)).toBe(1);
    expect(at(0)).toBe(0);
    expect(at(5_000, { ended: true })).toBe(0);
  });

  it("stays away when autoplay is off, the viewer cancelled or nothing follows", () => {
    expect(at(5_000, { autoplay: false })).toBeNull();
    expect(at(5_000, { cancelled: true })).toBeNull();
    expect(at(5_000, { hasNext: false })).toBeNull();
    expect(at(0, { ended: true, hasNext: false })).toBeNull();
  });

  it("waits for the length", () => {
    expect(countdown({ positionMs: 0, durationMs: 0, ended: false, autoplay: true, cancelled: false, hasNext: true })).toBeNull();
  });
});

// Frieren's opening at 3–93 s of a 1560 s file, off a real AniSkip answer.
const FILE_MS = 1_560_000;

function seconds(from: number, to: number) {
  return { startMs: from * 1_000, endMs: to * 1_000 };
}

describe("plausibleMarks", () => {
  it("keeps an opening in the first minutes and an ending reaching the last ones", () => {
    const marks = plausibleMarks([{ kind: "op", ...seconds(3, 93) }, { kind: "ed", ...seconds(1460, 1560) }], FILE_MS);

    expect(marks).toEqual({ opening: seconds(3, 93), ending: seconds(1460, 1560) });
  });

  it("drops intervals too short or too long to be an opening or an ending", () => {
    expect(plausibleMarks([{ kind: "op", ...seconds(10, 50) }, { kind: "ed", ...seconds(1300, 1560) }], FILE_MS)).toEqual(NO_MARKS);
    expect(plausibleMarks([{ kind: "op", ...seconds(10, 70) }, { kind: "ed", ...seconds(1410, 1560) }], FILE_MS)).toEqual({
      opening: seconds(10, 70),
      ending: seconds(1410, 1560),
    });
  });

  it("drops an opening past five minutes and an ending far from the end", () => {
    // Dandadan's «ending» at 5–95 s.
    expect(plausibleMarks([{ kind: "op", ...seconds(310, 400) }, { kind: "ed", ...seconds(5, 95) }], FILE_MS)).toEqual(NO_MARKS);
    expect(plausibleMarks([{ kind: "op", ...seconds(300, 390) }, { kind: "ed", ...seconds(1290, 1380) }], FILE_MS)).toEqual({
      opening: seconds(300, 390),
      ending: seconds(1290, 1380),
    });
  });

  it("drops what runs past the end of this file or before its start", () => {
    expect(plausibleMarks([{ kind: "ed", ...seconds(1460, 1560) }], 1_400_000)).toEqual(NO_MARKS);
    expect(plausibleMarks([{ kind: "op", startMs: -1_000, endMs: 89_000 }], FILE_MS)).toEqual(NO_MARKS);
    expect(plausibleMarks([{ kind: "op", ...seconds(3, 93) }], 0)).toEqual(NO_MARKS);
  });

  it("keeps the first plausible interval of each kind", () => {
    const marks = plausibleMarks(
      [
        { kind: "ed", ...seconds(5, 95) },
        { kind: "op", ...seconds(10, 50) },
        { kind: "op", ...seconds(3, 93) },
        { kind: "op", ...seconds(224, 314) },
        { kind: "ed", ...seconds(1460, 1560) },
        { kind: "ed", ...seconds(1440, 1530) },
      ],
      FILE_MS,
    );

    expect(marks).toEqual({ opening: seconds(3, 93), ending: seconds(1460, 1560) });
  });
});

describe("skipOffer", () => {
  const marks: SkipMarks = { opening: seconds(3, 93), ending: seconds(1460, 1560) };

  it("offers the opening for ten seconds from its start", () => {
    expect(skipOffer(marks, 2_999)).toBeNull();
    expect(skipOffer(marks, 3_000)).toBe("opening");
    expect(skipOffer(marks, 12_999)).toBe("opening");
    expect(skipOffer(marks, 13_000)).toBeNull();
  });

  it("offers the ending for ten seconds from its start", () => {
    expect(skipOffer(marks, 1_459_999)).toBeNull();
    expect(skipOffer(marks, 1_460_000)).toBe("ending");
    expect(skipOffer(marks, 1_470_000)).toBeNull();
  });

  it("asks about the opening first", () => {
    expect(skipOffer({ opening: seconds(0, 60), ending: seconds(0, 60) }, 5_000)).toBe("opening");
  });

  it("offers nothing without marks", () => {
    expect(skipOffer(NO_MARKS, 3_000)).toBeNull();
  });
});

describe("shouldAutoSkip", () => {
  const ending = seconds(1460, 1560);
  const playing = { enabled: true, done: false, ending, positionMs: 1_470_000, previousMs: 1_469_750, playedSinceSeekMs: 30_000 };

  it("steps over the ending ten seconds into it, while it still plays", () => {
    expect(shouldAutoSkip(playing)).toBe(true);
    expect(shouldAutoSkip({ ...playing, positionMs: 1_469_999, previousMs: 1_469_750 })).toBe(false);
    expect(shouldAutoSkip({ ...playing, positionMs: 1_560_000, previousMs: 1_559_750 })).toBe(false);
  });

  it("ignores a drag into the ending: the tick before has to be inside it already", () => {
    expect(shouldAutoSkip({ ...playing, previousMs: 600_000 })).toBe(false);
    expect(shouldAutoSkip({ ...playing, previousMs: 1_459_999 })).toBe(false);
    expect(shouldAutoSkip({ ...playing, previousMs: 1_460_000 })).toBe(true);
  });

  it("waits a second of play after a seek", () => {
    expect(shouldAutoSkip({ ...playing, playedSinceSeekMs: 999 })).toBe(false);
    expect(shouldAutoSkip({ ...playing, playedSinceSeekMs: 1_000 })).toBe(true);
  });

  it("does nothing with the setting off, once it fired, or without an ending", () => {
    expect(shouldAutoSkip({ ...playing, enabled: false })).toBe(false);
    expect(shouldAutoSkip({ ...playing, done: true })).toBe(false);
    expect(shouldAutoSkip({ ...playing, ending: null })).toBe(false);
  });
});
