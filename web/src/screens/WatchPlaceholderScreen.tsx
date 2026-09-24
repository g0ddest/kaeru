import { useParams } from "react-router-dom";
import { episodeBadge } from "../domain/format";
import { SecondaryButton } from "../ui/Button";
import { IconBack } from "../ui/icons";

/** Where the watch button leads until the player exists (decision 9). */
export function WatchPlaceholderScreen() {
  const { id = "", episode = "" } = useParams();
  const number = Number(episode);
  return (
    <main className="screen-center">
      {Number.isInteger(number) && number > 0 ? (
        <p className="screen-center__eyebrow t-label">{episodeBadge(number)}</p>
      ) : null}
      <h1 className="t-headline">Плеер появится в следующем обновлении</h1>
      <SecondaryButton to={`/anime/${id}`} icon={<IconBack />}>
        Назад к тайтлу
      </SecondaryButton>
    </main>
  );
}
