import SwiftUI

struct PlaybackRoute: Identifiable { let id = UUID(); var anime: Anime; var episode: Int }

struct DetailView: View {
    @Environment(AppModel.self) private var model
    let initial: Anime
    @State private var details: Anime?
    @State private var route: PlaybackRoute?
    @State private var failure: String?
    private var anime: Anime { details ?? initial }
    private var rate: LibraryItem? { model.rate(for: anime.id) }
    private var next: Int { continueEpisode(anime: anime, watched: rate?.episodes ?? 0, progress: model.progress[anime.id]) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 28) {
                        PosterView(anime: anime).frame(width: 200)
                        summary.frame(minWidth: 300, maxWidth: .infinity, alignment: .leading)
                    }
                    VStack(alignment: .leading, spacing: 22) {
                        PosterView(anime: anime).frame(width: 180).frame(maxWidth: .infinity)
                        summary
                    }
                }
                if let failure { Label(failure, systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(.secondary) }
                if !anime.plainDescription.isEmpty {
                    VStack(alignment: .leading, spacing: 10) { Text("Об аниме").font(.title2.bold()); Text(anime.plainDescription).foregroundStyle(.secondary).textSelection(.enabled) }
                }
                if anime.availableEpisodes > 0 {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Серии").font(.title2.bold())
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
                            ForEach(1...anime.availableEpisodes, id: \.self) { episode in
                                Button { route = PlaybackRoute(anime: anime, episode: episode) } label: {
                                    HStack {
                                        Image(systemName: episode <= (rate?.episodes ?? 0) ? "checkmark.circle.fill" : "play.circle")
                                        Text("Серия \(episode)"); Spacer(minLength: 0)
                                    }.padding(.vertical, 8)
                                }.buttonStyle(.bordered)
                            }
                        }
                    }
                } else { ContentUnavailableView("Серии ещё не вышли", systemImage: "calendar", description: Text("Добавьте аниме в планы, чтобы вернуться к нему позже.")) }
            }.padding().frame(maxWidth: 900).frame(maxWidth: .infinity)
        }
        .navigationTitle(anime.title).navigationBarTitleDisplayMode(.inline)
        .task(id: initial.id) {
            do { details = try await model.service.details(initial.id); failure = nil }
            catch is CancellationError {} catch { failure = error.localizedDescription }
        }
        .fullScreenCover(item: $route) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
    }
    private var summary: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(anime.title).font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
            if !anime.originalTitle.isEmpty { Text(anime.originalTitle).foregroundStyle(.secondary) }
            Text(anime.subtitle).font(.subheadline).foregroundStyle(.secondary)
            if next > 0 {
                Button { route = PlaybackRoute(anime: anime, episode: next) } label: {
                    Label("\(model.progress[anime.id] == nil && rate == nil ? "Смотреть" : "Продолжить") · серия \(next)", systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 4)
                }.buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("play-anime")
            }
            if model.session != nil {
                Menu {
                    ForEach(WatchStatus.allCases) { status in
                        Button { model.queueRate(anime: anime, status: status.rawValue, episodes: status == .completed ? anime.episodes : rate?.episodes ?? 0) } label: {
                            if rate?.status == status.rawValue { Label(status.title, systemImage: "checkmark") } else { Text(status.title) }
                        }
                    }
                } label: { Label(WatchStatus(rawValue: rate?.status ?? "")?.title ?? "В мой список", systemImage: rate == nil ? "plus" : "checkmark").frame(maxWidth: .infinity) }.buttonStyle(.bordered).controlSize(.large)
                if let rate {
                    Stepper("Просмотрено: \(rate.episodes)", value: Binding(get: { model.rate(for: anime.id)?.episodes ?? 0 }, set: { model.queueRate(anime: anime, status: rate.status, episodes: $0) }), in: 0...max(anime.episodes, anime.episodesAired, rate.episodes))
                        .font(.subheadline)
                }
            }
        }
    }
}
