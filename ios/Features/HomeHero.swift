import SwiftUI

/// The top of the home screen: the artwork of what you are in the middle of, edge to edge, with one
/// bright button on it.
///
/// Three to five titles take turns every seven seconds. The turn-taking stops for good the moment a
/// finger lands on it, and never starts at all when the viewer has asked the system to reduce
/// motion — a picture that moves under a reader is worse than one that does not move at all.
struct HeroCarousel: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let titles: [Anime]
    let height: CGFloat
    let play: (Anime) -> Void
    @State private var index = 0
    @State private var touched = false

    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(titles.enumerated()), id: \.element.id) { position, anime in
                HeroPage(anime: anime) { play(anime) }.tag(position)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: height)
        .overlay(alignment: .bottom) { dots }
        .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in touched = true })
        .task(id: "\(index)/\(touched)") { await advance() }
        .onChange(of: titles.map(\.id)) { _, ids in if index >= ids.count { index = 0 } }
    }

    @ViewBuilder private var dots: some View {
        if titles.count > 1 {
            HStack(spacing: 7) {
                ForEach(titles.indices, id: \.self) { position in
                    Circle().fill(.white.opacity(position == index ? 0.95 : 0.35)).frame(width: 7, height: 7)
                }
            }
            .padding(.bottom, 14)
            .animation(.easeOut(duration: 0.25), value: index)
            .accessibilityHidden(true)
        }
    }

    private func advance() async {
        guard titles.count > 1, !touched, !reduceMotion else { return }
        try? await Task.sleep(for: .seconds(7))
        guard !Task.isCancelled, !touched else { return }
        withAnimation(.easeInOut(duration: 0.55)) { index = (index + 1) % titles.count }
    }
}

private struct HeroPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var typeSize
    let anime: Anime
    let play: () -> Void
    private var target: ContinueTarget { model.continueTarget(for: anime) }
    private var caption: String {
        let progress = model.progressFor(animeID: anime.id, episode: target.episode)
        if target.rewatch { return "Пересмотреть с первой серии" }
        if target.position > 0, let progress, progress.duration > target.position {
            return "Продолжить \(target.episode) серию · осталось \(Int(ceil((progress.duration - target.position) / 60))) мин"
        }
        return target.position > 0 ? "Продолжить \(target.episode) серию" : "Смотреть \(target.episode) серию"
    }
    var body: some View {
        // Bottom-left on a phone, where the artwork is behind the type; vertically centred on a
        // wide screen, where the picture has room beside it and a low caption leaves a void.
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                Text(anime.title)
                    .font(.kaeruHero(sizeClass != .regular))
                    .foregroundStyle(.white)
                    .lineLimit(3).minimumScaleFactor(0.7)
                    .fixedSize(horizontal: false, vertical: true)
                Text(caption)
                    .font(.subheadline).foregroundStyle(.white.opacity(0.85))
                    .lineLimit(typeSize.isAccessibilitySize ? 4 : 2)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: play) {
                    Label(target.position > 0 ? "Продолжить" : "Смотреть", systemImage: "play.fill")
                        .font(.headline).lineLimit(1).minimumScaleFactor(0.6)
                        .padding(.horizontal, 22).padding(.vertical, 12)
                        .background(.white, in: Capsule())
                        .foregroundStyle(.black)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home-resume")
                .padding(.top, 4)
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, Metrics.gutter(sizeClass))
            .padding(.bottom, sizeClass == .regular ? 0 : 34)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity,
               alignment: sizeClass == .regular ? .leading : .bottomLeading)
        .background { Backdrop(anime: anime) }
        // The status bar sits on this artwork — the hero runs under it on purpose — and a clock in
        // white over a pale poster is unreadable, in either appearance. One short fall of shadow
        // along the top, the same shadow the bottom of the hero already casts.
        .overlay(alignment: .top) {
            LinearGradient(stops: [
                .init(color: .black.opacity(0.55), location: 0),
                .init(color: .black.opacity(0.18), location: 0.55),
                .init(color: .clear, location: 1)
            ], startPoint: .top, endPoint: .bottom)
            .frame(height: 132)
            .allowsHitTesting(false)
        }
        .contentShape(Rectangle())
    }
}
