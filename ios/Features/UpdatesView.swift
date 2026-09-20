import SwiftUI
import UIKit

/// Where the home row and the settings row both lead. A route rather than a sheet: this is a page
/// somebody went looking for, and it belongs in the stack they walked to get here.
struct UpdatesRoute: Hashable {}

/// «Обновления»: what is installed, what is published, and one press between them.
///
/// Written as the settings page is — two named parts and prose inside them, with the control at
/// the end of the sentence that explains it — because this is the same kind of page. It is not a
/// dialog and not a notification: nothing here interrupts, nothing is dismissed, and the screen is
/// only ever reached by somebody who went looking for it.
///
/// There is one amber button on screen at a time, and it is always the same press: «Установить»,
/// which hands iOS the release and steps out of the way. «Проверить» is quiet beside it, because
/// pressing it is how you find out there is nothing to do.
struct UpdatesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var updates: UpdateModel?

    var body: some View {
        ScrollView {
            if let updates {
                VStack(alignment: .leading, spacing: 32) {
                    section(UpdateCopy.installed) {
                        note(UpdateCopy.installedLine(updates.installedVersion))
                    }
                    section(UpdateCopy.latest) {
                        latest(updates)
                        // Under the block it is about, never in place of it: a release already
                        // found stays on screen when the check that would have refreshed it fails.
                        if let message = updates.message {
                            Text(message).font(.subheadline).foregroundStyle(.red)
                                .accessibilityIdentifier("update-error")
                        }
                        if updates.canCheck {
                            Button(UpdateCopy.check) { updates.check() }
                                .buttonStyle(.bordered).tint(Palette.inkSoft)
                                .accessibilityIdentifier("update-check")
                        }
                    }
                }
                .padding(.horizontal, Metrics.gutter(sizeClass))
                .padding(.vertical, 24)
                .frame(maxWidth: Metrics.contentWidth, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Palette.canvas)
        .navigationTitle(UpdateCopy.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard updates == nil else { return }
            let value = UpdateModel(repository: model.updateRepository,
                                    installedVersion: model.updateRepository.installedVersion) { url in
                await UIApplication.shared.open(url)
            }
            updates = value
            // Unforced: the app already asked at launch, and a second request a minute later would
            // spend the hourly budget on an answer it has in hand.
            value.check(force: false)
        }
        .onChange(of: updates?.stage) { _, _ in model.refreshAvailableUpdate() }
    }

    /// The four states of the page, each with the one control that belongs under it.
    @ViewBuilder private func latest(_ updates: UpdateModel) -> some View {
        switch updates.stage {
        case .checking:
            note(UpdateCopy.checking)
            ProgressView().progressViewStyle(.linear).tint(Palette.accent)
        case .unknown:
            note(UpdateCopy.unknown)
        case .upToDate:
            headline(UpdateCopy.upToDate)
            if let checked = UpdateCopy.checkedLine(updates.checkedAt) { note(checked) }
        case .available:
            if let release = updates.release {
                headline(UpdateCopy.available(release.version))
                if let line = UpdateCopy.releaseLine(release) { note(line) }
                if !release.notes.isEmpty {
                    Text(release.notes).font(.subheadline).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // One press either way, and the sentence above it says which door it opens: iOS
                // cannot install this app itself, and a button that pretended otherwise would be
                // a button that silently does nothing.
                note(release.install != nil ? UpdateCopy.installNote : UpdateCopy.pageNote)
                Button(release.install != nil ? UpdateCopy.install : UpdateCopy.openPage) { updates.install() }
                    .buttonStyle(.borderedProminent).tint(Palette.accent)
                    .foregroundStyle(Palette.onAccent)
                    .accessibilityIdentifier("update-install")
            }
        }
    }

    /// What this part of the page is about: a version, an outcome, a state.
    private func headline(_ text: String) -> some View {
        Text(text).font(.headline).foregroundStyle(Palette.ink)
    }

    /// The quiet line under it: a date, a size, a sentence saying what happens next.
    private func note(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(Palette.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// One named part of the page: a quiet heading and prose under it, no card and no rule.
    private func section<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.footnote.weight(.semibold)).foregroundStyle(Palette.inkSoft)
                .textCase(.uppercase).kerning(0.6)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One line at the top of the home screen saying a newer version exists, and leading to it.
///
/// Modelled on `OfflineStrip` and deliberately as quiet: surface colour, secondary text, no badge,
/// no colour, no animation. An update is not news the app should interrupt anybody with — nothing
/// is wrong, nothing is expiring, and the episode they opened the app for is two shelves below. So
/// it is stated once, at the top, in three words, and by a row rather than by a sheet, because a
/// sheet would have to be dismissed.
///
/// The one thing it has that the offline strip does not is a chevron: it goes somewhere, and the
/// chevron is how every other row in this app says so.
struct UpdateStrip: View {
    let version: String
    var body: some View {
        NavigationLink(value: UpdatesRoute()) {
            HStack(spacing: 8) {
                Text(UpdateCopy.available(version))
                    .font(.subheadline).foregroundStyle(Palette.inkSoft).lineLimit(1)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    .foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20).padding(.vertical, 11)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(Palette.surface)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // One announcement rather than two: the sentence is what this row is.
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("update-strip")
    }
}
