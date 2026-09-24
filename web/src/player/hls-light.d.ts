// hls.js ships types for the full build only; the light build exports the same API minus features
// Kodik never uses (subtitles, alternate audio, DRM).
declare module "hls.js/light" {
  export * from "hls.js";
  export { default } from "hls.js";
}
