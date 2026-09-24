import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import { beginSignIn } from "../auth/signin";
import { PrimaryButton } from "../ui/Button";

export interface SignInScreenProps {
  /** Why the viewer is here again, e.g. «Сессия истекла, войдите снова». */
  message: string | null;
  begin?: (returnTo: string) => string;
  navigateTo?: (url: string) => void;
}

function openPage(url: string): void {
  window.location.assign(url);
}

/** Without a session there is nothing but this screen (spec §6a). Copy: Android LoginScreen. */
export function SignInScreen({ message, begin = beginSignIn, navigateTo = openPage }: SignInScreenProps) {
  const location = useLocation();
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    // Back from Shikimori through the bfcache: the page is live again, so is the button.
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setLeaving(false);
    };
    window.addEventListener("pageshow", onPageShow);
    return () => window.removeEventListener("pageshow", onPageShow);
  }, []);

  function signIn() {
    setLeaving(true);
    // After sign-in the viewer lands where they were, deep link included.
    navigateTo(begin(location.pathname + location.search + location.hash));
  }

  return (
    <main className="screen-center">
      <h1 className="t-display">Kaeru</h1>
      <p className="screen-center__text t-body">
        Войдите через Shikimori, чтобы синхронизировать список и просмотренные серии.
      </p>
      <PrimaryButton onClick={signIn} disabled={leaving}>
        Войти через Shikimori
      </PrimaryButton>
      {message ? (
        <p className="screen-center__error t-body" role="alert">
          {message}
        </p>
      ) : null}
    </main>
  );
}
