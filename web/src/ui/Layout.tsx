import { useEffect, useId, useRef, type ReactNode } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigationType } from "react-router-dom";
import type { Account } from "../api/shikimori";
import { useAccess } from "../auth/session";
import { IconHome, IconLibrary, IconPerson, IconSearch } from "./icons";

function Avatar({ account }: { account: Account | null }) {
  if (account?.avatar) return <img className="avatar" src={account.avatar} alt="" width={30} height={30} />;
  return (
    <span className="avatar avatar--empty" aria-hidden="true">
      <IconPerson size={30} />
    </span>
  );
}

/**
 * The signed-in frame. From 768px: a sidebar with Главная and Поиск, then «Библиотека» → Мой список,
 * and the account row at the bottom. Narrower: a top bar with the account link and a bottom tab bar
 * in Android's order. This top bar is the app's only one: Home draws no bar of its own over the hero.
 * CSS shows one of the two sets, so only one is ever exposed.
 */
export function Layout({ children }: { children?: ReactNode }) {
  const access = useAccess();
  const account = access.kind === "signed_in" ? access.session.account : null;
  const libraryId = useId();
  const { pathname } = useLocation();
  const navigationType = useNavigationType();

  const shownPath = useRef(pathname);

  useEffect(() => {
    const moved = shownPath.current !== pathname;
    shownPath.current = pathname;
    // A new page starts at the top. Back and forward keep the browser's position, and so does a page
    // that rewrites its own address in place (search updating ?q= as you type).
    if (navigationType === "POP" || (navigationType === "REPLACE" && !moved)) return;
    (document.scrollingElement ?? document.documentElement).scrollTop = 0;
  }, [pathname, navigationType]);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Перейти к содержимому
      </a>
      <aside className="sidebar">
        <span className="wordmark t-title">Kaeru</span>
        <nav className="sidebar__nav" aria-label="Разделы">
          <NavLink to="/" end className="nav-item t-title-sm">
            <IconHome size={22} />
            <span>Главная</span>
          </NavLink>
          <NavLink to="/search" className="nav-item t-title-sm">
            <IconSearch size={22} />
            <span>Поиск</span>
          </NavLink>
          <div className="nav-group" role="group" aria-labelledby={libraryId}>
            <span id={libraryId} className="nav-group__label t-label-sm">
              Библиотека
            </span>
            <NavLink to="/list" className="nav-item t-title-sm">
              <IconLibrary size={22} />
              <span>Мой список</span>
            </NavLink>
          </div>
        </nav>
        <Link to="/settings" className="account-row" aria-label="Аккаунт и настройки">
          <Avatar account={account} />
          <span className="account-row__name t-title-sm">{account?.nickname ?? "Гость"}</span>
        </Link>
      </aside>
      <header className="topbar">
        <span className="wordmark t-title">Kaeru</span>
        <Link to="/settings" className="topbar__account" aria-label="Аккаунт и настройки">
          <Avatar account={account} />
        </Link>
      </header>
      <main id="main" className="main" tabIndex={-1}>
        {children ?? <Outlet />}
      </main>
      <nav className="tabbar" aria-label="Разделы">
        <NavLink to="/" end className="tab-item">
          <span className="tab-item__icon">
            <IconHome />
          </span>
          <span className="tab-item__label">Главная</span>
        </NavLink>
        <NavLink to="/list" className="tab-item">
          <span className="tab-item__icon">
            <IconLibrary />
          </span>
          <span className="tab-item__label">Мой список</span>
        </NavLink>
        <NavLink to="/search" className="tab-item">
          <span className="tab-item__icon">
            <IconSearch />
          </span>
          <span className="tab-item__label">Поиск</span>
        </NavLink>
      </nav>
    </div>
  );
}
