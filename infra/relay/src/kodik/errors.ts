/** Why Kodik could not give what was asked, in words the routes can turn into a status. */
export type KodikErrorKind = "title" | "episode" | "token" | "parser" | "upstream";

export class KodikError extends Error {
  constructor(readonly kind: KodikErrorKind, readonly step?: string) {
    super(step === undefined ? `kodik ${kind}` : `kodik ${kind}: ${step}`);
  }
}
