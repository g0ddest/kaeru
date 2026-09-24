import { useEffect, useMemo } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useAccess } from "../auth/session";
import { AuthCallbackScreen } from "../screens/AuthCallbackScreen";
import { HomeScreen } from "../screens/HomeScreen";
import { LibraryScreen } from "../screens/LibraryScreen";
import { SearchScreen } from "../screens/SearchScreen";
import { SettingsScreen } from "../screens/SettingsScreen";
import { TitleScreen } from "../screens/TitleScreen";
import { WatchPlaceholderScreen } from "../screens/WatchPlaceholderScreen";
import { Layout } from "../ui/Layout";
import { ToastProvider } from "../ui/Toast";
import { Gate } from "./Gate";
import { createServices, ServicesProvider, useServices, type Services } from "./services";

export function App({ services }: { services?: Services }) {
  return (
    <BrowserRouter>
      <AppRoutes services={services} />
    </BrowserRouter>
  );
}

/** The route table without a router, so a test can mount it in a MemoryRouter. */
export function AppRoutes({ services }: { services?: Services }) {
  const access = useAccess();
  const accountId = access.kind === "signed_in" ? access.session.account.id : null;
  // One set of services per account: another account must never see the previous list.
  const current = useMemo(() => services ?? createServices(), [services, accountId]);

  return (
    <ServicesProvider services={current}>
      <ToastProvider>
        <Routes>
          {/* The OAuth return is the one page outside the gate: it is the way through it. */}
          <Route path="/auth" element={<AuthCallbackScreen />} />
          <Route
            element={
              <Gate>
                <SignedIn />
              </Gate>
            }
          >
            {/* Full window, no bars: the player takes this route in the next plan. */}
            <Route path="/watch/:id/:episode" element={<WatchPlaceholderScreen />} />
            <Route element={<Layout />}>
              <Route path="/" element={<HomeScreen />} />
              <Route path="/search" element={<SearchScreen />} />
              <Route path="/list" element={<LibraryScreen />} />
              <Route path="/anime/:id" element={<TitleScreen />} />
              <Route path="/settings" element={<SettingsScreen />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Route>
        </Routes>
      </ToastProvider>
    </ServicesProvider>
  );
}

function SignedIn() {
  const { library } = useServices();
  useEffect(() => {
    // Every screen reads the list: start it here unless a screen already has.
    // A failure is kept in library.state(), where the screens show it.
    if (library.state().kind === "idle") void library.load().catch(() => undefined);
  }, [library]);
  return <Outlet />;
}
