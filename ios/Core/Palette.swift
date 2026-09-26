import SwiftUI

/// Every colour, corner and shelf measurement the app draws with.
///
/// The dark values are the Android client's palette to the hex, so the two Kaeru apps read as one
/// family; the light values are its cool mirror rather than a warm paper, so a phone set to light
/// looks deliberate instead of bleached. Nine colours and no tenth: the screens take their colour
/// from posters, and the amber is spent only on progress and on what the viewer is meant to press.
enum Palette {
    /// The page. It matches what the navigation and tab bars paint, so no seam runs across the
    /// top of a screen; the card surface steps away from it — up in the dark, down in the light.
    static let canvas = adaptive(dark: 0x0B0C10, light: 0xFFFFFF)
    /// A card, a sidebar, a row — the one raised surface.
    static let surface = adaptive(dark: 0x15171E, light: 0xF2F3F7)
    /// A surface on a surface: a selected sidebar row, a tile inside a card.
    static let elevated = adaptive(dark: 0x1E212B, light: 0xE8EAF0)
    /// The only line colour in the app: card outlines, dividers, the resting border of a control.
    static let hairline = adaptive(dark: 0x2A2E3A, light: 0xDDE0E7)
    static let ink = adaptive(dark: 0xF2F3F5, light: 0x14161B)
    static let inkSoft = adaptive(dark: 0x9AA0AA, light: 0x5C636E)
    /// Kaeru's amber. Darkened in light mode, where the dark value cannot hold a legible edge.
    static let accent = adaptive(dark: 0xF5A524, light: 0x9A5B00)
    /// What is written on the amber. Android's `KaeruOnAccent` in the dark, where white on
    /// `#F5A524` is under two to one and reads as a smudge; white in the light, where the amber is
    /// dark enough to carry it.
    static let onAccent = adaptive(dark: 0x1A1200, light: 0xFFFFFF)

    /// What lies over artwork so type can sit on it. Always black, in both appearances: it is a
    /// shadow cast on the picture, not a colour of the page.
    static func scrim(_ strength: Double = 1) -> LinearGradient {
        LinearGradient(stops: [
            .init(color: .black.opacity(0), location: 0),
            .init(color: .black.opacity(0.35 * strength), location: 0.55),
            .init(color: .black.opacity(0.82 * strength), location: 1)
        ], startPoint: .top, endPoint: .bottom)
    }

    private static func adaptive(dark: Int, light: Int) -> Color {
        #if os(iOS)
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
        #else
        // The provider is asked while drawing, on whatever thread draws, so it holds the two
        // numbers and nothing else. `bestMatch` rather than comparing names: the high-contrast
        // appearances are dark or light too, and a plain `==` would paint them light.
        Color(nsColor: NSColor(name: nil) { appearance in
            NSColor(rgb: appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light)
        })
        #endif
    }
}

#if os(iOS)
private extension UIColor {
    convenience init(rgb: Int) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
#else
private extension NSColor {
    convenience init(rgb: Int) {
        self.init(srgbRed: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
#endif

/// Distances. A screen picks its numbers from here and nowhere else, so the gutter down the left of
/// the home screen is the same line as the gutter on the library and on a title.
enum Metrics {
    static func gutter(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 32 : 20 }
    /// Between one shelf and the next. Wide enough that no divider is needed to separate them.
    static func shelfSpacing(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 36 : 24 }
    /// Between cards inside a shelf.
    static func cardSpacing(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 18 : 12 }
    /// A 16:9 card's width; its height follows.
    static func stillWidth(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 340 : 252 }
    /// A 2:3 poster's width in a shelf.
    static func posterWidth(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 168 : 128 }
    /// The narrowest a poster may be in a grid — three across a phone, as many as fit on an iPad.
    static func gridPosterWidth(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 168 : 106 }
    /// How much of the window the hero takes. The phone gives it more, having nothing beside it.
    static func heroFraction(_ sizeClass: UserInterfaceSizeClass?) -> CGFloat { sizeClass == .regular ? 0.40 : 0.45 }
    /// The reading width a page of text or a grid is allowed to reach on a wide screen.
    static let contentWidth: CGFloat = 1280

    static let cardRadius: CGFloat = 14
    static let posterRadius: CGFloat = 12
    static let tileRadius: CGFloat = 10
    static let hairline: CGFloat = 1
}

/// The type roles. Display type is condensed: Russian anime titles are long, and the narrower face
/// keeps them on one line while giving the headings a weight the body text never reaches.
extension Font {
    static func kaeruHero(_ compact: Bool = false) -> Font {
        .system(.largeTitle, design: .default, weight: .heavy).width(.condensed)
    }
    static func kaeruShelf(_ compact: Bool) -> Font {
        .system(compact ? .title3 : .title2, design: .default, weight: .bold).width(.condensed)
    }
    static let kaeruCardTitle = Font.subheadline.weight(.semibold)
    static let kaeruCardCaption = Font.caption.weight(.medium)
}

extension View {
    /// The one card treatment: a soft corner and a single hairline, no shadow.
    func kaeruCard(radius: CGFloat = Metrics.cardRadius) -> some View {
        clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Palette.hairline, lineWidth: Metrics.hairline)
            }
    }
}
