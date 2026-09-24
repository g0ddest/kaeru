import type { ReactNode } from "react";
import { useAccess } from "../auth/session";
import { AccessClosedScreen } from "../screens/AccessClosedScreen";
import { SignInScreen } from "../screens/SignInScreen";

/** Nothing but sign-in until the worker has let this account in (spec §6, §6a). */
export function Gate({ children }: { children: ReactNode }) {
  const access = useAccess();
  if (access.kind === "signed_out") return <SignInScreen message={access.message} />;
  if (access.kind === "closed") return <AccessClosedScreen nickname={access.nickname} />;
  return <>{children}</>;
}
