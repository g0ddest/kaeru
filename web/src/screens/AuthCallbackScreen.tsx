import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { sessionStore, useAccess } from "../auth/session";
import { beginSignIn, completeSignIn, type SignInResult } from "../auth/signin";
import { PrimaryButton } from "../ui/Button";
import { IndeterminateStrip } from "../ui/States";

const UNKNOWN_ERROR = "Что-то пошло не так. Повторите попытку";

export interface AuthCallbackScreenProps {
  complete?: (search: string) => Promise<SignInResult>;
  begin?: (returnTo: string) => string;
  navigateTo?: (url: string) => void;
}

function openPage(url: string): void {
  window.location.assign(url);
}

/** /auth: Shikimori sends the browser back here with ?code&state, and a code works only once. */
export function AuthCallbackScreen({
  complete = completeSignIn,
  begin = beginSignIn,
  navigateTo = openPage,
}: AuthCallbackScreenProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const access = useAccess();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    // Once per visit, StrictMode's second effect run included.
    if (started.current) return;
    started.current = true;
    const search = location.search;
    // A reload must never resend a spent code, so the address loses it first.
    if (search !== "") navigate(location.pathname, { replace: true });
    complete(search).then(
      (result) => {
        if (!mounted.current) return;
        if (result.kind === "done") {
          navigate(result.returnTo, { replace: true });
        } else if (result.kind === "closed") {
          // completeSignIn records it too; setting it here keeps the gate right whatever the order.
          sessionStore.setClosed(result.nickname);
          navigate("/", { replace: true });
        } else {
          setError(result.message);
        }
      },
      () => {
        if (mounted.current) setError(UNKNOWN_ERROR);
      },
    );
  }, [complete, location, navigate]);

  function retry() {
    // Signed out: a fresh authorization, since the old code is spent. Signed in: nothing to redo.
    if (access.kind === "signed_out") navigateTo(begin("/"));
    else navigate("/", { replace: true });
  }

  if (error === null) {
    return (
      <main className="screen-center">
        <p className="t-title" role="status">
          Проверяем код…
        </p>
        <IndeterminateStrip />
      </main>
    );
  }

  return (
    <main className="screen-center">
      <h1 className="t-display">Kaeru</h1>
      <p className="screen-center__error t-body" role="alert">
        {error}
      </p>
      <PrimaryButton onClick={retry}>Войти ещё раз</PrimaryButton>
    </main>
  );
}
