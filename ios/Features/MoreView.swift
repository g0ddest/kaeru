import SwiftUI

/// The phone's fifth tab: who you are, the screens that involve another device, and the settings.
/// On iPad the same three things live in the sidebar and in the row pinned under it.
struct MoreView: View {
    @Environment(AppModel.self) private var model
    let openSettings: () -> Void
    var body: some View {
        List {
            Section {
                Button(action: openSettings) {
                    HStack(spacing: 14) {
                        AsyncImage(url: URL(string: model.session?.account.avatar ?? "")) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(Palette.inkSoft)
                        }
                        .frame(width: 46, height: 46).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            Text(model.session?.account.nickname ?? "Гость").font(.kaeruHeadline).foregroundStyle(Palette.ink)
                            Text(model.session == nil ? "Войдите в Shikimori" : "Аккаунт и настройки")
                                .font(.kaeruFootnote).foregroundStyle(Palette.inkSoft)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(.kaeruCaption.weight(.semibold))
                            .foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
                    }
                    .padding(.vertical, 6).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Аккаунт и настройки")
            }
            Section("Устройства") {
                NavigationLink { DevicePairingView(embedded: true) } label: { Label("Телевизор", systemImage: "tv") }
                NavigationLink { TogetherView(manager: model.together, embedded: true) } label: {
                    Label(TogetherCopy.watchTogether, systemImage: "person.2.wave.2")
                }
            }
            Section {
                Button(action: openSettings) { Label("Настройки", systemImage: "gearshape") }
                    .foregroundStyle(Palette.ink)
            }
        }
        .tint(Palette.accent)
    }
}
