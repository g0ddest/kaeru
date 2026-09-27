import { pluralEpisodes, statusLabel } from "../domain/format";
import type { Card } from "../domain/feed";
import { LIST_TABS, SECRET_LABEL, availableEpisodes } from "../domain/models";
import type { EpisodeProgress, LibraryEntry, ListStatus } from "../domain/models";
import { continueTarget, episodeFraction } from "../domain/progress";

export type LibrarySort = "updated" | "title";

export const SORT_OPTIONS: readonly { value: LibrarySort; label: string }[] = [
  { value: "updated", label: "Обновление" },
  { value: "title", label: "Название" },
];

/** A list status, or «Украдкой»: titles Shikimori is not told about. */
export type LibraryTabKey = ListStatus | "secret";

export interface LibraryTab {
  status: LibraryTabKey;
  count: number;
  text: string;
}

export interface EmptyTabCopy {
  title: string;
  text: string;
  offersSearch: boolean;
}

// Built once: constructing a collator is the expensive half of sorting by name.
const titleOrder = new Intl.Collator("ru", { sensitivity: "accent" });

function tabOf(entry: LibraryEntry): LibraryTabKey {
  return entry.secret === true ? "secret" : entry.rate.status;
}

// Every status keeps its tab, zero included; counts cover the whole list, not the open tab.
// «Украдкой» comes last and only while something is secret or it is the tab open.
export function libraryTabs(entries: readonly LibraryEntry[], open?: LibraryTabKey): LibraryTab[] {
  const counts = new Map<LibraryTabKey, number>();
  for (const entry of entries) counts.set(tabOf(entry), (counts.get(tabOf(entry)) ?? 0) + 1);
  const tabs: LibraryTab[] = LIST_TABS.map((status) => {
    const count = counts.get(status) ?? 0;
    return { status, count, text: `${statusLabel(status)} ${count}` };
  });
  const secret = counts.get("secret") ?? 0;
  if (secret > 0 || open === "secret") tabs.push({ status: "secret", count: secret, text: `${SECRET_LABEL} ${secret}` });
  return tabs;
}

// Both orders end in a name tie-break, so a list imported in one second never reshuffles.
export function selectLibrary(
  entries: readonly LibraryEntry[],
  status: LibraryTabKey,
  sort: LibrarySort,
): LibraryEntry[] {
  const byName = (a: LibraryEntry, b: LibraryEntry) =>
    titleOrder.compare(a.anime.title, b.anime.title) || a.anime.id - b.anime.id;
  const order =
    sort === "updated"
      ? (a: LibraryEntry, b: LibraryEntry) => b.rate.updatedAt - a.rate.updatedAt || byName(a, b)
      : byName;
  return entries.filter((entry) => tabOf(entry) === status).sort(order);
}

// Android libraryCardSubtitle: «7 из 28» once started, the season length before, nothing when unknown.
export function libraryCardSubtitle(entry: LibraryEntry): string | null {
  const total = entry.anime.episodes > 0 ? entry.anime.episodes : availableEpisodes(entry.anime);
  const watched = entry.rate.episodes;
  if (watched > 0) return `${watched} из ${total > 0 ? total : "?"}`;
  if (total > 0) return pluralEpisodes(total);
  return null;
}

// Android LibraryEntry.progressFraction: a strip only inside the episode being continued.
export function libraryCardProgress(
  entry: LibraryEntry,
  progress: readonly EpisodeProgress[],
  threshold: number,
): number | null {
  const target = continueTarget(entry, progress, threshold);
  if (target.positionMs <= 0) return null;
  return episodeFraction(entry, progress, target.episode, threshold);
}

export function libraryCard(entry: LibraryEntry, progress: readonly EpisodeProgress[], threshold: number): Card {
  return {
    key: String(entry.anime.id),
    animeId: entry.anime.id,
    title: entry.anime.title,
    posterUrl: entry.anime.posterUrl,
    badge: null,
    subtitle: libraryCardSubtitle(entry),
    progress: libraryCardProgress(entry, progress, threshold),
  };
}

// Android LibraryTabs.emptyTabCopy; only the tabs a viewer fills on purpose offer search.
export function emptyTabCopy(status: LibraryTabKey): EmptyTabCopy {
  switch (status) {
    case "secret":
      return {
        title: "Украдкой ничего не смотрите",
        text: "Выберите «Украдкой» в статусе тайтла — серии будут отмечаться только здесь, без Shikimori.",
        offersSearch: false,
      };
    case "watching":
      return {
        title: "Вы ничего не смотрите",
        text: "Начните любой тайтл — он окажется здесь вместе с серией, на которой вы остановились.",
        offersSearch: true,
      };
    case "planned":
      return {
        title: "В планах пока пусто",
        text: "Складывайте сюда всё, что хотите посмотреть потом. Kaeru покажет, когда выйдут новые серии.",
        offersSearch: true,
      };
    case "completed":
      return {
        title: "Завершённых тайтлов пока нет",
        text: "Здесь соберётся всё, что вы досмотрели до конца.",
        offersSearch: false,
      };
    case "rewatching":
      return {
        title: "Вы ничего не пересматриваете",
        text: "Отметьте тайтл как «Пересматриваю» — он появится здесь.",
        offersSearch: false,
      };
    case "on_hold":
      return {
        title: "Ничего не отложено",
        text: "Тайтлы на паузе ждут здесь. Вернуться к ним можно в любой вечер.",
        offersSearch: false,
      };
    case "dropped":
      return {
        title: "Ничего не брошено",
        text: "Тайтлы, которые не пошли, собираются здесь, чтобы не мешать остальным.",
        offersSearch: false,
      };
  }
}

export function parseTab(raw: string | null): LibraryTabKey {
  if (raw === "secret") return "secret";
  return LIST_TABS.find((status) => status === raw) ?? "watching";
}

export function parseSort(raw: string | null): LibrarySort {
  return raw === "title" ? "title" : "updated";
}
