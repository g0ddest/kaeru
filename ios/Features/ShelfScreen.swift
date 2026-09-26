import SwiftUI

/// A shelf opened in full: the same cards as the row, laid out as a grid.
struct ShelfScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    let route: ShelfRoute
    @State private var playing: PlaybackRoute?
    var body: some View {
        ScrollView {
            CatalogGrid(still: route.episodes) {
                // «Скачано» carries its own episodes: two of one title are two cards there, so the
                // rows are keyed on the episode rather than on the show.
                ForEach(route.downloads) { item in
                    DownloadedCard(item: item) { play(item) }
                }
                ForEach(route.anime) { anime in
                    if route.episodes {
                        let target = model.continueTarget(for: anime)
                        EpisodeCard(anime: anime, target: target,
                                    progress: model.progressFor(animeID: anime.id, episode: target.episode)) { play(anime) }
                    } else {
                        NavigationLink(value: anime) { AnimeCard(anime: anime) }
                            .buttonStyle(.plain)
                            .contextMenu { LibraryStatusMenu(anime: anime) }
                    }
                }
            }
            .padding(.horizontal, Metrics.gutter(sizeClass))
            .padding(.vertical, 20)
            .frame(maxWidth: Metrics.contentWidth).frame(maxWidth: .infinity)
        }
        .background(Palette.canvas)
        .navigationTitle(route.title)
        .kaeruTitleDisplay(.inline)
        .playerPresentation(item: $playing)
    }
    private func play(_ item: DownloadedShelf.Item) {
        model.beginPlayback(anime: item.anime)
        playing = PlaybackRoute(anime: item.anime, episode: item.episode)
    }
    private func play(_ anime: Anime) {
        let target = model.continueTarget(for: anime)
        guard target.canPlay else { return }
        model.beginPlayback(anime: anime)
        playing = PlaybackRoute(anime: anime, episode: target.episode)
    }
}
