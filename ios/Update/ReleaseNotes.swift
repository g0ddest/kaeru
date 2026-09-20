import Foundation

/// A release body, as a screen with no markdown renderer on it can show.
///
/// The app has no markdown engine and is not getting one for this: a release note is a handful of
/// lines, and the whole of what markup does for it is make a list look like a list. So the markup
/// is removed rather than rendered, and the two things that carry meaning without it — the line
/// breaks and the bullets — are kept. `## Что нового` becomes «Что нового», `- исправлен плеер`
/// becomes «• исправлен плеер», and `[выпуск](https://…)` becomes «выпуск», because a URL nobody
/// can tap is noise in the middle of a sentence.
///
/// Fenced code is left exactly as written. It is the one part of a release note where the
/// characters are the content, and stripping asterisks out of a diff would corrupt it.
///
/// Rule for rule the same as Android's `ReleaseNotes.kt`: the two apps read one repository's
/// release notes, and a note that renders differently on the two phones is a note somebody has to
/// check twice.
enum ReleaseNotes {
    /// The bullet every list marker becomes, so `-`, `*` and `+` read as one kind of thing.
    private static let bulletGlyph = "• "

    private static let htmlComment = expression("<!--(.|\\n)*?-->")
    private static let fence = expression("^\\s{0,3}(```|~~~)")
    private static let heading = expression("^\\s{0,3}#{1,6}\\s*")
    private static let rule = expression("^\\s{0,3}([-*_])([ \\t]*\\1){2,}[ \\t]*$")
    private static let quote = expression("^\\s{0,3}>\\s?")
    private static let bullet = expression("^([ \\t]*)[-*+][ \\t]+")
    private static let setext = expression("^\\s{0,3}(=+|-{2,})\\s*$")

    private static let image = expression("!\\[([^\\]]*)]\\([^)]*\\)")
    private static let link = expression("\\[([^\\]]*)]\\([^)]*\\)")
    private static let referenceLink = expression("\\[([^\\]]*)]\\[[^\\]]*]")
    private static let autolink = expression("<((?:https?|mailto):[^>\\s]+)>")
    private static let htmlTag = expression("</?[A-Za-z][^>]*>")
    private static let boldStar = expression("\\*\\*(.+?)\\*\\*")
    private static let boldUnderscore = expression("__(.+?)__")
    private static let italicStar = expression("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")
    private static let italicUnderscore = expression("(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])")
    private static let strikethrough = expression("~~(.+?)~~")
    private static let inlineCode = expression("`([^`]+)`")
    private static let escape = expression("\\\\([\\\\`*_{}\\[\\]()#+\\-.!~>|])")
    private static let blankRun = expression("\\n{3,}")
    /// The gap a removed image or link leaves in the middle of a sentence. Indentation is left alone.
    private static let doubleSpace = expression("(\\S)[ \\t]{2,}")

    static func plain(_ body: String?) -> String {
        guard let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return "" }
        let normalized = body.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let withoutComments = replacing(htmlComment, in: normalized, with: "")
        var fenced = false
        var lines: [String] = []
        for raw in withoutComments.components(separatedBy: "\n") {
            if contains(fence, raw) {
                // The fence itself is markup; what it wrapped is not.
                fenced.toggle()
            } else if fenced {
                lines.append(trimmingTrailing(raw))
            } else if matchesWhole(rule, raw) || matchesWhole(setext, raw) {
                continue
            } else {
                lines.append(plainLine(raw))
            }
        }
        let joined = replacing(blankRun, in: lines.joined(separator: "\n"), with: "\n\n")
        return joined.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func plainLine(_ raw: String) -> String {
        let unquoted = replacing(quote, in: raw, with: "")
        let unheaded = replacing(heading, in: unquoted, with: "")
        let bulleted = replacing(bullet, in: unheaded, with: "$1" + bulletGlyph)
        return trimmingTrailing(replacing(doubleSpace, in: inline(bulleted), with: "$1 "))
    }

    /// The markup that sits inside a line.
    ///
    /// Order matters in two places: an image is a link with a `!` in front of it, so it goes first
    /// or its alt text survives as a stray word; and the two-character emphases go before the
    /// one-character ones, or `**x**` leaves a pair of asterisks behind.
    private static func inline(_ line: String) -> String {
        var value = replacing(image, in: line, with: "")
        value = replacing(link, in: value, with: "$1")
        value = replacing(referenceLink, in: value, with: "$1")
        value = replacing(autolink, in: value, with: "$1")
        value = replacing(htmlTag, in: value, with: "")
        value = replacing(boldStar, in: value, with: "$1")
        value = replacing(boldUnderscore, in: value, with: "$1")
        value = replacing(strikethrough, in: value, with: "$1")
        value = replacing(italicStar, in: value, with: "$1")
        value = replacing(italicUnderscore, in: value, with: "$1")
        value = replacing(inlineCode, in: value, with: "$1")
        return replacing(escape, in: value, with: "$1")
    }

    private static func expression(_ pattern: String) -> NSRegularExpression {
        // Every pattern here is a literal in this file: one that will not compile is a programmer
        // error at the first call, not a note that renders oddly on somebody's phone.
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: pattern)
    }

    private static func replacing(_ expression: NSRegularExpression, in value: String, with template: String) -> String {
        expression.stringByReplacingMatches(in: value, range: NSRange(value.startIndex..., in: value), withTemplate: template)
    }

    private static func contains(_ expression: NSRegularExpression, _ value: String) -> Bool {
        expression.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil
    }

    private static func matchesWhole(_ expression: NSRegularExpression, _ value: String) -> Bool {
        let whole = NSRange(value.startIndex..., in: value)
        guard let match = expression.firstMatch(in: value, range: whole) else { return false }
        return match.range == whole
    }

    private static func trimmingTrailing(_ value: String) -> String {
        var result = value
        while let last = result.last, last.isWhitespace { result.removeLast() }
        return result
    }
}
