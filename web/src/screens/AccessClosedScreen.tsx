import { sessionStore } from "../auth/session";
import { SecondaryButton } from "../ui/Button";

export interface AccessClosedScreenProps {
  nickname: string;
  onSignOut?: () => void;
}

/** The account is not on the worker's list: its own nickname and a way out, no catalogue (spec §6a). */
export function AccessClosedScreen({ nickname, onSignOut = () => sessionStore.signOut() }: AccessClosedScreenProps) {
  return (
    <main className="screen-center">
      <h1 className="t-headline">Доступ закрыт</h1>
      <p className="screen-center__nickname t-title">{nickname}</p>
      <p className="screen-center__text t-body">Kaeru для браузера открыт по приглашению</p>
      <p className="screen-center__text t-body">Попросите владельца добавить ваш аккаунт Shikimori в список.</p>
      <SecondaryButton onClick={onSignOut}>Выйти</SecondaryButton>
    </main>
  );
}
