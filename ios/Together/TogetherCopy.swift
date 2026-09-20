import Foundation

/// Everything a shared viewing says, in one place — a port of Android's `TogetherCopy`.
///
/// Here rather than in the screens because the same sentence is said in two of them: the overlay
/// over the video and the form in Settings are different views, and a copy of «Смотреть одному» in
/// each is two strings that will one day disagree about what the way out of a broken session is
/// called.
///
/// Verbs are written «поставил(а)» rather than guessed. The name comes from Shikimori, which has no
/// gender on it, and a phone that tells a woman she «поставил на паузу» is a phone that got a fact
/// wrong to save two characters.
enum TogetherCopy {
    // --- entering ---------------------------------------------------------------------------
    static let watchTogether = "Смотреть вместе"
    static let leave = "Выйти из совместного просмотра"
    static let share = "Поделиться приглашением"
    /// The one name for somebody Shikimori never named.
    static let somebody = "Друг"
    // --- waiting ----------------------------------------------------------------------------
    static let waitingFriend = "Ждём друга…"
    static let connecting = "Подключаемся…"
    static let reconnecting = "Восстанавливаем связь…"
    static let keepWatching = "Смотреть дальше"
    static let watchAlone = "Смотреть одному"
    static let unreachable = "Не удалось подключиться"
    // --- talking ----------------------------------------------------------------------------
    static let writePlaceholder = "Написать…"
    static let you = "Вы"
    static let emptyHistory = "Пока тихо"
    static let history = "Показать переписку"
    static let reactions = "Реакции"
    static let voice = "Записать голосовое"
    static let voiceHint = "Удерживайте, чтобы записать"
    static let voiceCancel = "◀ отмена"
    static let replay = "Послушать ещё раз"
    static let micDenied = "Нужен доступ к микрофону"
    static let send = "Отправить"
    static let close = "Закрыть"
    /// Three taps that cover most of what gets said, so the keyboard stays shut.
    static let presets = ["😂", "Стоп, что?", "Дальше!"]
    /// What a chat message may be before the protocol says no. Android's ceiling, applied at the
    /// keyboard so the limit is something a person runs into rather than a silent truncation.
    static let maxChars = 200
    // --- ending -----------------------------------------------------------------------------
    static let ended = "Сессия закончилась"
    static let leftSession = "Вы вышли из совместного просмотра"

    /// The episode by its number. «7 серия» is the seventh one, where the numeral is an ordinal and
    /// the noun does not agree with it — which is also why the accusative is a separate string.
    static func episodeNominative(_ episode: Int) -> String { "\(episode) серия" }
    static func episodeAccusative(_ episode: Int) -> String { "\(episode) серию" }

    /// «Смотрим «Проводы в последний путь», 7 серию. Открой в Kaeru: …»
    static func shareText(title: String, episode: Int, link: String) -> String {
        "Смотрим «\(title)», \(episodeAccusative(episode)). Открой в Kaeru: \(link)"
    }

    /// What the other phone just did. One line, no full stop, and always with a name in front of it.
    static func notice(_ kind: TogetherNoticeKind, peerName: String?, positionMs: Int64 = 0, episode: Int = 0) -> String {
        let who = name(peerName)
        switch kind {
        case .paused: return "\(who) поставил(а) на паузу"
        case .played: return "\(who) включил(а)"
        case .seeked: return "\(who) перемотал(а) на \(time(positionMs))"
        case .episode: return "\(who) включил(а) \(episodeAccusative(episode))"
        case .joined: return "\(who) подключился(ась)"
        case .left: return "\(who) вышел(ла)"
        case .catchingUp: return "\(who) догоняет…"
        }
    }

    /// Why it stopped. Never «что-то пошло не так»: each of these is a different thing to do next.
    static func lost(_ error: TogetherError?) -> String {
        switch error {
        case .roomFull: return "В этой сессии уже двое"
        case .notConfigured: return "Сервер совместного просмотра не настроен"
        case .timeout, .expired: return unreachable
        default: return "Связь с другом потеряна"
        }
    }

    static func exitLabel(_ exit: TogetherWaitExit) -> String {
        exit == .keepWatching ? keepWatching : watchAlone
    }

    /// `0:07` — a clip's length, read the same way as a position on the timeline.
    static func clipLength(_ durationMs: Int64) -> String { time(durationMs) }

    /// What the player's own control says while something is going on, or nil for the plain icon.
    static func sessionChip(phase: TogetherPhase, peerName: String?) -> String? {
        switch phase {
        case .live: return peerName.map(name) ?? waitingFriend
        case .connecting: return connecting
        case .reconnecting: return reconnecting
        case .idle, .ended, .failed: return nil
        }
    }

    /// Whoever is on the other phone, named.
    static func name(_ peerName: String?) -> String {
        let trimmed = peerName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? somebody : trimmed
    }

    /// `12:04`, and `1:02:03` once an episode runs past the hour. Android's `formatTime`.
    static func time(_ ms: Int64) -> String {
        guard ms > 0 else { return "0:00" }
        let total = ms / 1000
        let seconds = total % 60, minutes = (total / 60) % 60, hours = total / 3600
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    /// What a screen reader says instead of the picture.
    static func reactionName(_ reaction: TogetherReaction) -> String {
        switch reaction {
        case .heart: return "Сердце"
        case .laugh: return "Смех"
        case .wow: return "Удивление"
        case .sad: return "Грусть"
        case .fire: return "Огонь"
        case .clap: return "Аплодисменты"
        }
    }
}
