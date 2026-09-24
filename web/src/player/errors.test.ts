// Copy: android/src/main/java/app/kaeru/ui/common/ErrorMessages.kt; the second button:
// android/src/main/java/app/kaeru/ui/common/player/PlayerFailure.kt.
import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/http";
import { EngineError, failureAction, playerMessage } from "./errors";
import { KodikError } from "./kodik";

describe("playerMessage", () => {
  it("says the title is not on Kodik at all", () => {
    expect(playerMessage(new KodikError("title"), 5)).toBe("Серия ещё не появилась в Kodik");
  });

  it("names the episode the dub lacks when it is known", () => {
    expect(playerMessage(new KodikError("episode"), 5)).toBe("Серии 5 ещё нет в этой озвучке");
    expect(playerMessage(new KodikError("episode"))).toBe("Этой серии ещё нет в выбранной озвучке");
  });

  it("names the episode no dub has yet when it is known", () => {
    expect(playerMessage(new KodikError("nowhere"), 5)).toBe("Серия 5 пока не вышла ни в одной озвучке");
    expect(playerMessage(new KodikError("nowhere"))).toBe("Серия пока не вышла ни в одной озвучке");
  });

  it("says Kodik gave no key", () => {
    expect(playerMessage(new KodikError("token"))).toBe("Kodik недоступен: не удалось получить ключ");
  });

  it("blames Kodik for a refusal, a closed gate and a stream that failed on the network", () => {
    for (const error of [new KodikError("upstream"), new KodikError("unavailable"), new EngineError("network")]) {
      expect(playerMessage(error)).toBe("Kodik временно недоступен, попробуйте позже");
    }
  });

  it("says the source changed when the worker could not read Kodik", () => {
    expect(playerMessage(new KodikError("parser"))).toBe("Источник обновился, ждите обновления приложения");
  });

  it("asks to wait out the worker's rate limit", () => {
    expect(playerMessage(new KodikError("throttled"))).toBe("Слишком много запросов, попробуйте позже");
    // authorized rethrows a token refresh the worker throttled as this.
    expect(playerMessage(new ApiError(429))).toBe("Слишком много запросов, попробуйте позже");
  });

  it("names the connection when nothing answered", () => {
    for (const error of [new KodikError("offline"), new EngineError("offline"), new NetworkError()]) {
      expect(playerMessage(error)).toBe("Нет соединения. Проверьте интернет");
    }
  });

  it("sends a session Shikimori no longer accepts back to sign-in", () => {
    expect(playerMessage(new ApiError(401))).toBe("Сессия истекла, войдите снова");
  });

  it("says the browser cannot play the stream", () => {
    expect(playerMessage(new EngineError("unsupported"))).toBe("Этот браузер не умеет показывать это видео");
  });

  it("falls back to the generic copy for a broken stream and anything unforeseen", () => {
    for (const error of [new EngineError("media"), new KodikError("unknown"), new ApiError(500), new Error("boom"), "boom", null]) {
      expect(playerMessage(error)).toBe("Что-то пошло не так. Повторите попытку");
    }
  });
});

describe("failureAction", () => {
  it("leads back to the episode list when no dub can help", () => {
    expect(failureAction(new KodikError("title"))).toBe("list");
    expect(failureAction(new KodikError("nowhere"))).toBe("list");
  });

  it("offers another dub otherwise", () => {
    expect(failureAction(new KodikError("episode"))).toBe("dub");
    expect(failureAction(new EngineError("network"))).toBe("dub");
    expect(failureAction(new Error("boom"))).toBe("dub");
  });
});
