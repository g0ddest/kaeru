import { createContext, useContext, type ReactNode } from "react";
import { createShikimoriHttp } from "../api/http";
import { createShikimori, type Shikimori } from "../api/shikimori";
import { authorized, sessionStore, type SessionStore } from "../auth/session";
import { Library } from "../library/library";
import { progressStore, type ProgressStore } from "../library/progress";
import { createAniSkip, type AniSkip } from "../player/aniskip";
import { createEngine, type EngineFactory } from "../player/engine";
import { createKodik, type Kodik } from "../player/kodik";

export interface Services {
  shikimori: Shikimori;
  library: Library;
  progress: ProgressStore;
  /** The worker's Kodik routes. */
  kodik: Kodik;
  aniskip: AniSkip;
  /** Builds the engine for the player's one `<video>`. */
  engine: EngineFactory;
}

/** The real object graph; tests pass a fake fetch or a separate session store. */
export function createServices(
  deps: { fetch?: typeof fetch; store?: SessionStore; progress?: ProgressStore } = {},
): Services {
  const store = deps.store ?? sessionStore;
  const progress = deps.progress ?? progressStore;
  // Pass fetch only when given, so the browser's own fetch keeps its binding.
  const shikimori = createShikimori(createShikimoriHttp(deps.fetch ? { fetch: deps.fetch } : undefined));
  // Writes and the list go through this store's token, whichever store the caller chose.
  const auth: typeof authorized = (call, options) => authorized(call, { ...options, store });
  const library = new Library({
    shikimori,
    authorized: auth,
    accountId: () => {
      const access = store.get();
      return access.kind === "signed_in" ? access.session.account.id : null;
    },
    progress,
  });
  // An account the worker takes off its allow-list closes this store's session, as a refresh would.
  const kodik = createKodik({
    authorized: auth,
    onClosed: (nickname) => store.setClosed(nickname),
    ...(deps.fetch ? { fetch: deps.fetch } : {}),
  });
  const aniskip = createAniSkip(deps.fetch ? { fetch: deps.fetch } : {});
  return { shikimori, library, progress, kodik, aniskip, engine: createEngine };
}

export const ServicesContext = createContext<Services | null>(null);

export function ServicesProvider({ services, children }: { services: Services; children: ReactNode }) {
  return <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>;
}

export function useServices(): Services {
  const services = useContext(ServicesContext);
  if (services === null) throw new Error("useServices() is used outside <ServicesProvider>");
  return services;
}
