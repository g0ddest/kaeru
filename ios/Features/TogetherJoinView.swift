import SwiftUI

/// What a link in a messenger opens.
///
/// A port of Android's `JoinScreen`, and the same shape for the same reason: a phone in a chat is
/// held upright, so this is one centred column read top to bottom. The artwork says which show
/// without being read, the sentence under it says who, which episode and from which minute, and
/// the button says the only thing there is to do.
///
/// Until the other phone answers there is a room id and nothing else, so the poster and the
/// sentence wait as placeholders rather than as plausible-looking defaults, and the button waits
/// with them: there is no agreeing to something that has not said what it is yet.
///
/// Two ways out, always both on screen. «Не сейчас» is the ordinary one; the other is whatever the
/// failure left possible, which is why an error replaces the invitation instead of sitting under it.
struct TogetherJoinView: View {
    @Environment(AppModel.self) private var model
    @Bindable var manager: TogetherManager
    /// «Смотреть вместе» — the room is already joined, this opens this phone's own copy.
    var onWatch: (TogetherEpisode) -> Void
    /// A link that can be knocked on again.
    var onRetry: () -> Void
    var onDismiss: () -> Void

    @State private var anime: Anime?
    @ScaledMetric(relativeTo: .largeTitle) private var posterWidth = 168.0

    private var target: TogetherJoinTarget { manager.joining ?? TogetherJoinTarget() }

    var body: some View {
        ZStack {
            Palette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 24) {
                    poster
                    if let failure = target.failure { failed(failure) }
                    else if let episode = target.episode { invitation(episode) }
                    else { waiting }
                }
                .frame(maxWidth: 420)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.vertical, 40)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        // The title arrives with the greeting, not with the link, so this runs again when the
        // room finally says what it is.
        .task(id: target.episode?.animeID) {
            guard let id = target.episode?.animeID, id > 0, anime?.id != id else { return }
            anime = await model.anime(id: id)
        }
        .interactiveDismissDisabled()
    }

    @ViewBuilder private var poster: some View {
        Group {
            if let anime { PosterView(anime: anime) }
            else {
                RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous)
                    .fill(Palette.surface)
                    .overlay {
                        RoundedRectangle(cornerRadius: Metrics.tileRadius, style: .continuous)
                            .strokeBorder(Palette.hairline, lineWidth: Metrics.hairline)
                    }
                    // A poster-shaped placeholder rather than a spinner in a box: what is missing
                    // here is the artwork, and the column must not resize when it lands.
                    .overlay { Image(systemName: "person.2.fill").font(.kaeruLargeTitle).foregroundStyle(Palette.inkSoft) }
            }
        }
        .frame(width: posterWidth, height: posterWidth * 1.5)
        .accessibilityHidden(true)
    }

    private var waiting: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text(TogetherCopy.connecting).font(.kaeruHeadline).foregroundStyle(Palette.ink)
            Text("Ждём, что скажет друг — какую серию и с какой минуты.")
                .font(.kaeruSubheadline).foregroundStyle(Palette.inkSoft).multilineTextAlignment(.center)
            Button(TogetherCopy.notNow) { onDismiss() }
                .buttonStyle(.plain).foregroundStyle(Palette.accent).padding(.top, 8)
        }
    }

    private func invitation(_ episode: TogetherEpisode) -> some View {
        VStack(spacing: 12) {
            Text(TogetherCopy.invites(target.peerName))
                .font(.kaeruTitle2.weight(.semibold)).foregroundStyle(Palette.ink)
                .multilineTextAlignment(.center)
            Text(line(episode))
                .font(.kaeruSubheadline).foregroundStyle(Palette.inkSoft).multilineTextAlignment(.center)
            Button { onWatch(episode) } label: {
                Text(TogetherCopy.join).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(Palette.accent).foregroundStyle(Palette.onAccent)
            .controlSize(.large)
            .padding(.top, 16)
            Button(TogetherCopy.notNow) { onDismiss() }
                .buttonStyle(.plain).foregroundStyle(Palette.accent)
            // Said here rather than at the first press of the microphone: this is the moment a
            // person decides whether to be in a conversation at all.
            Text(TogetherCopy.micNote)
                .font(.kaeruFootnote).foregroundStyle(Palette.inkSoft)
                .multilineTextAlignment(.center).padding(.top, 8)
        }
    }

    private func failed(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text(message)
                .font(.kaeruHeadline).foregroundStyle(Palette.ink).multilineTextAlignment(.center)
            if target.retryable {
                Button { onRetry() } label: { Text(TogetherCopy.retry).frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).tint(Palette.accent).foregroundStyle(Palette.onAccent)
                    .controlSize(.large).padding(.top, 16)
            }
            Button(TogetherCopy.notNow) { onDismiss() }
                .buttonStyle(.plain).foregroundStyle(Palette.accent).padding(.top, target.retryable ? 0 : 16)
        }
    }

    private func line(_ episode: TogetherEpisode) -> String {
        guard let anime else { return TogetherCopy.episodeNominative(episode.episode) }
        return TogetherCopy.joinLine(peerName: target.peerName, title: anime.title,
                                     episode: episode.episode, positionMs: episode.positionMs)
    }
}
