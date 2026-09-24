import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useServices } from "../app/services";
import { sessionStore, useAccess } from "../auth/session";
import {
  QUALITY_CHOICES,
  THRESHOLD_CHOICES,
  autoplayNext,
  defaultQuality,
  setAutoplayNext,
  setDefaultQuality,
  setSkipEnding,
  setWatchedThreshold,
  skipEnding,
  watchedThreshold,
} from "../library/prefs";
import { DestructiveButton } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { PillGroup } from "../ui/Pill";
import type { PillOption } from "../ui/Pill";
import { posterLetter } from "../ui/PosterCard";
import { Switch } from "../ui/Switch";
import "./browse.css";

// Android's words (SettingsScreen.kt, SettingsSections.kt).
const NO_NAME = "Имя не загрузилось";
const THRESHOLD = "Порог просмотра";
const THRESHOLD_NOTE = "Серия считается просмотренной после этой доли";
const AUTOPLAY = "Следующая серия автоматически";
const SKIP_ENDING = "Пропускать эндинг";
// Said under the switch: closing the player after the last episode would otherwise read as a crash.
const SKIP_ENDING_NOTE =
  "Через 10 секунд начнётся следующая серия. После последней плеер закроется и вернёт на карточку";
const QUALITY = "Качество по умолчанию";
const AUTO = "Авто";
/** «Авто» in the pill row, which takes numbers only; stored as no value at all. */
const AUTO_VALUE = 0;

const QUALITY_OPTIONS: PillOption<number>[] = QUALITY_CHOICES.map((height) =>
  height === null ? { value: AUTO_VALUE, label: AUTO } : { value: height, label: `${height}p` },
);

// Two values that round to the same whole percent are one choice (Android thresholdChosen).
function sameChoice(option: number, current: number): boolean {
  return Math.abs(option - current) < 0.005;
}

// «80 %» is one word in Russian: a no-break space keeps the sign with the number.
function percent(fraction: number): string {
  return `${Math.round(fraction * 100)}\u00A0%`;
}

// The four shares, plus a stored one that is none of them, in its place (Android thresholdOptions):
// a row with nothing lit would claim the setting is one of four values when it is a fifth.
function thresholdOptions(current: number): PillOption<number>[] {
  const offered = THRESHOLD_CHOICES.some((choice) => sameChoice(choice, current))
    ? [...THRESHOLD_CHOICES]
    : [...THRESHOLD_CHOICES, current];
  return offered.sort((a, b) => a - b).map((value) => ({ value, label: percent(value) }));
}

/** Account and sign-out, then playback: the watched threshold (decision 10), next episode, ending, quality. */
export function SettingsScreen() {
  const access = useAccess();
  const { progress } = useServices();
  const navigate = useNavigate();
  const [threshold, setThreshold] = useState(() => watchedThreshold());
  const [autoplay, setAutoplay] = useState(() => autoplayNext());
  const [skipsEnding, setSkipsEnding] = useState(() => skipEnding());
  const [quality, setQuality] = useState(() => defaultQuality());
  const [confirming, setConfirming] = useState(false);
  const account = access.kind === "signed_in" ? access.session.account : null;
  const name = account !== null && account.nickname !== "" ? account.nickname : NO_NAME;
  const options = thresholdOptions(threshold);
  const chosen = options.find((option) => sameChoice(option.value, threshold))?.value ?? threshold;

  function choose(value: number) {
    setWatchedThreshold(value);
    // Read back: storage that refused the write keeps the old value, and the row must say so.
    setThreshold(watchedThreshold());
  }

  // Each control writes through and reads back, like the threshold.
  function chooseAutoplay(on: boolean) {
    setAutoplayNext(on);
    setAutoplay(autoplayNext());
  }

  function chooseSkipEnding(on: boolean) {
    setSkipEnding(on);
    setSkipsEnding(skipEnding());
  }

  function chooseQuality(value: number) {
    setDefaultQuality(value === AUTO_VALUE ? null : value);
    setQuality(defaultQuality());
  }

  function signOut() {
    // The dialog promises the local cache goes: every position this browser kept, titles played
    // from search included, not only the ones in the loaded list (Android wipes it on sign-out).
    progress.clear();
    // Signed out on "/", so the next sign-in lands home rather than back in settings.
    navigate("/", { replace: true });
    sessionStore.signOut();
  }

  return (
    <div className="page">
      <h1 className="page-title t-headline">Настройки</h1>
      <div className="browse-body set-body">
        <section className="set-section" aria-labelledby="set-account">
          <h2 id="set-account" className="t-title set-section__title">
            Аккаунт
          </h2>
          <div className="set-account">
            {account?.avatar ? (
              <img className="set-account__avatar" src={account.avatar} alt="" width={56} height={56} />
            ) : (
              <span className="set-account__avatar set-account__avatar--letter" aria-hidden="true">
                {posterLetter(name)}
              </span>
            )}
            <div className="set-account__text">
              <p className="t-title set-account__name">{name}</p>
              <p className="t-body set-account__source">Shikimori</p>
            </div>
          </div>
          <DestructiveButton onClick={() => setConfirming(true)}>Выйти из аккаунта</DestructiveButton>
        </section>
        <section className="set-section" aria-labelledby="set-playback">
          <h2 id="set-playback" className="t-title set-section__title">
            Воспроизведение
          </h2>
          <div className="set-group">
            <p className="t-title-sm">{THRESHOLD}</p>
            <p className="t-body set-note">{THRESHOLD_NOTE}</p>
            <PillGroup
              kind="radio"
              label={THRESHOLD}
              options={options}
              value={chosen}
              onChange={choose}
              className="set-choices"
            />
          </div>
          <div className="set-group">
            <Switch label={AUTOPLAY} checked={autoplay} onChange={chooseAutoplay} />
            <Switch label={SKIP_ENDING} note={SKIP_ENDING_NOTE} checked={skipsEnding} onChange={chooseSkipEnding} />
          </div>
          <div className="set-group">
            <p className="t-title-sm">{QUALITY}</p>
            <PillGroup
              kind="radio"
              label={QUALITY}
              options={QUALITY_OPTIONS}
              value={quality ?? AUTO_VALUE}
              onChange={chooseQuality}
              className="set-choices"
            />
          </div>
        </section>
      </div>
      <Dialog
        open={confirming}
        title="Выйти из аккаунта?"
        text="Список и прогресс останутся на Shikimori, локальный кэш будет очищен"
        confirmLabel="Выйти"
        cancelLabel="Отмена"
        destructive
        onConfirm={signOut}
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
