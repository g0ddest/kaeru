import SwiftUI

/// The colours of things drawn on top of a picture rather than on a page.
///
/// Always these, in both appearances: they are shadows cast on the video, not surfaces of the app,
/// and a «light mode» chat bubble over a night scene is unreadable in either.
private enum OnVideo {
    static let disc = Color.black.opacity(0.42)
    static let slate = Color.black.opacity(0.62)
    static let ink = Color.white
    static let muted = Color(red: 0.84, green: 0.85, blue: 0.87)
    /// The one line that keeps a chip visible where the picture happens to be black — a letterbox
    /// bar, a fade to black, a scene at night. Without it a shadow cast on a shadow is nothing.
    static let edge = Color.white.opacity(0.16)
    /// How wide the conversation is allowed to get before it starts covering the picture.
    static let columnWidth: CGFloat = 360
    /// Where the bottom of the conversation sits: clear of AVKit's transport bar, which owns the
    /// bottom of the picture and whose visibility this app is not told about.
    static let aboveControls: CGFloat = 104
}

/// People talking over a video.
///
/// Two places, and the split is the whole idea. What the machine did — a pause that came from the
/// other phone, a friend catching up — appears at the top, centred, for three seconds. What a
/// person said appears at the bottom left, stacked, newest underneath. If both spoke from the same
/// corner, «Вася поставил на паузу» would read as something Вася typed.
///
/// Nothing here is written down. Tapping the stack opens the session's own history, which exists
/// for exactly as long as the session does; that tap is also what makes the seven-second fade
/// acceptable under WCAG 2.2.1, because it is the other way to the same information.
struct TogetherOverlay: View {
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Bindable var manager: TogetherManager
    /// The speaker for arriving clips. Held here rather than in the button, because a clip that
    /// arrives plays itself and nobody has to have pressed anything.
    @State private var speaker = VoicePlayer()
    @State private var microphoneOpen = false

    private var conversation: TogetherConversation { manager.conversation }

    var body: some View {
        ZStack {
            TogetherReactionBurst(reactions: conversation.reactions)
            VStack(spacing: 8) {
                if let notice = conversation.notice { Notice(text: notice.text) }
                if let wait = conversation.wait { Wait(wait: wait) { manager.leaveWait() } }
                // One line said once and forgotten: a goodbye, a microphone that was refused.
                if let message = conversation.message {
                    Notice(text: message)
                        .task(id: message) {
                            try? await Task.sleep(for: .seconds(3))
                            guard !Task.isCancelled else { return }
                            conversation.message = nil
                        }
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 12)
            .frame(maxWidth: .infinity, alignment: .top)
            if manager.phase == .live || manager.phase == .reconnecting {
                VStack(alignment: .leading, spacing: 8) {
                    Spacer(minLength: 0)
                    Stack(items: conversation.stack, replay: conversation.replay) { conversation.historyOpen = true }
                    Controls(manager: manager, microphoneOpen: $microphoneOpen)
                }
                .frame(maxWidth: OnVideo.columnWidth, alignment: .leading)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(.leading, 16)
                .padding(.bottom, OnVideo.aboveControls)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: conversation.notice)
        .animation(.easeInOut(duration: 0.2), value: conversation.wait)
        // A screen reader cannot be made to race a seven-second fade: WCAG 2.2.1. The corner then
        // empties only when the session does, and the history is still the other way to all of it.
        .onChange(of: voiceOver, initial: true) { _, on in conversation.autoHide = !on }
        // A clip arriving goes straight through the speaker; one this viewer recorded does not,
        // because they have just heard themselves say it.
        .onChange(of: conversation.playing?.id) { _, _ in
            guard let clip = conversation.playing?.clip else { speaker.stop(); return }
            let played = speaker.play(clip.data) { conversation.clipPlayed() }
            // Android records Ogg/Opus, which no Apple decoder reads. The clip arrived whole and
            // is refused here rather than swallowed, because silence would read as a bug.
            if !played { conversation.message = TogetherError.unsupportedVoice.errorDescription }
        }
        // The episode is turned down from the first frame of a hold to whatever ends it, and for
        // as long as a clip is coming out of the speaker. When the two overlap it stays down
        // until the last of them ends, which is what the `||` is.
        .onChange(of: microphoneOpen || conversation.playing != nil) { _, quiet in manager.duck(quiet) }
        .onDisappear { speaker.stop(); manager.duck(false) }
        .sheet(isPresented: Binding(get: { conversation.historyOpen },
                                    set: { conversation.historyOpen = $0 })) {
            TogetherHistorySheet(conversation: conversation)
        }
    }
}

private extension View {
    /// The one treatment for anything drawn over a picture: a dark capsule with a hairline on it.
    func onVideoChip(_ fill: Color = OnVideo.slate) -> some View {
        background(fill, in: Capsule())
            .overlay { Capsule().strokeBorder(OnVideo.edge, lineWidth: 1) }
    }
    func onVideoDisc(_ fill: Color = OnVideo.disc) -> some View {
        background(fill, in: Circle())
            .overlay { Circle().strokeBorder(OnVideo.edge, lineWidth: 1) }
    }
}

/// One line at the top about what the other phone did.
private struct Notice: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.footnote.weight(.medium)).foregroundStyle(OnVideo.ink).lineLimit(1)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .onVideoChip()
            // Read out as it arrives, without taking focus off whatever the viewer was on.
            .accessibilityAddTraits(.updatesFrequently)
            .transition(.opacity)
    }
}

/// A wait, and the button that ends it.
///
/// There is no arrangement of this without the button: `TogetherWait` does not exist without an
/// exit, which is the one rule this whole feature is built to keep.
private struct Wait: View {
    let wait: TogetherWait
    var exit: () -> Void
    var body: some View {
        HStack(spacing: 4) {
            Text(wait.text).font(.footnote.weight(.medium)).foregroundStyle(OnVideo.ink).lineLimit(1)
            Button(TogetherCopy.exitLabel(wait.exit), action: exit)
                .font(.footnote.weight(.semibold)).foregroundStyle(Palette.accent)
                .padding(.horizontal, 6)
        }
        .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 6)
        .onVideoChip()
        .transition(.opacity)
    }
}

/// The corner: at most three things, newest at the bottom, each fading out on its own clock.
private struct Stack: View {
    let items: [TogetherSaid]
    var replay: (Int64) -> Void
    var open: () -> Void
    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(items) { item in
                    Bubble(item: item) { replay(item.id) }
                        // Driven by the item's own flag rather than by its presence in the list: a
                        // line removed from a list has nothing left to animate, which is why it is
                        // marked on its way out and taken away a fade later.
                        .opacity(item.leaving ? 0 : 1)
                        .animation(.easeInOut(duration: TogetherConversationTiming.itemFadeMs / 1000), value: item.leaving)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: open)
            .accessibilityHint(TogetherCopy.history)
        }
    }
}

/// One thing somebody said: a line, or a clip with the length of it and a way to hear it again.
private struct Bubble: View {
    let item: TogetherSaid
    var replay: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Text(item.author).font(.footnote.weight(.medium))
                .foregroundStyle(item.mine ? OnVideo.muted : Palette.accent).lineLimit(1)
            if let clip = item.clip {
                Button(action: replay) {
                    Label(TogetherCopy.clipLength(clip.durationMs), systemImage: "play.fill")
                        .font(.footnote).foregroundStyle(OnVideo.ink).monospacedDigit()
                }
                .buttonStyle(.plain).accessibilityLabel(TogetherCopy.replay)
            } else {
                Text(item.text ?? "").font(.subheadline).foregroundStyle(OnVideo.ink).lineLimit(2)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .onVideoChip()
    }
}

/// Everything a person can say, in one row above the timeline.
///
/// One row and not three: in landscape there is about a finger's worth of space under the picture,
/// and a column of presets over a field over two buttons is all of it. The keyboard is the last
/// resort rather than the default — a tap on 😀 or on a preset says something without one, which
/// is why those are in front of the field rather than behind it.
private struct Controls: View {
    @Bindable var manager: TogetherManager
    @Binding var microphoneOpen: Bool
    @State private var picking = false
    @State private var composing = false
    @State private var draft = ""
    @State private var pickerRevision = 0
    @FocusState private var writing: Bool

    var body: some View {
        if composing {
            HStack(spacing: 8) {
                TextField(TogetherCopy.writePlaceholder, text: $draft)
                    .textFieldStyle(.plain).foregroundStyle(OnVideo.ink).tint(Palette.accent)
                    .submitLabel(.send).focused($writing)
                    .onSubmit(send)
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .onVideoChip()
                Button(TogetherCopy.send, action: send)
                    .font(.footnote.weight(.semibold)).foregroundStyle(Palette.accent)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button(TogetherCopy.close) { composing = false; draft = "" }
                    .font(.footnote).foregroundStyle(OnVideo.muted)
            }
            .frame(width: OnVideo.columnWidth)
            .onAppear { writing = true }
        } else {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    disc("😀", label: TogetherCopy.reactions) { picking.toggle(); pickerRevision += 1 }
                    VoiceButton(send: { manager.send(voice: $0) },
                                denied: { manager.conversation.message = TogetherCopy.micDenied },
                                openChanged: { microphoneOpen = $0 })
                    if picking {
                        ForEach(TogetherReaction.allCases, id: \.self) { reaction in
                            disc(reaction.symbol, label: TogetherCopy.reactionName(reaction)) {
                                manager.send(reaction: reaction)
                                picking = false
                            }
                        }
                    } else {
                        ForEach(TogetherCopy.presets, id: \.self) { preset in
                            pill(preset) { manager.send(chat: preset) }
                        }
                        pill(TogetherCopy.writePlaceholder, muted: true) { composing = true }
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
            .frame(maxWidth: OnVideo.columnWidth, alignment: .leading)
            .animation(.easeInOut(duration: 0.15), value: picking)
            // The row of six closes itself when nobody picks one.
            .task(id: pickerRevision) {
                guard picking else { return }
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled else { return }
                picking = false
            }
        }
    }

    private func send() {
        manager.send(chat: draft)
        draft = ""; composing = false; writing = false
    }
    private func disc(_ glyph: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(glyph).font(.title3).frame(width: 44, height: 44).onVideoDisc()
        }
        .buttonStyle(.plain).accessibilityLabel(label)
    }
    private func pill(_ text: String, muted: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.footnote.weight(.medium)).foregroundStyle(muted ? OnVideo.muted : OnVideo.ink)
                .lineLimit(1).padding(.horizontal, 16).frame(height: 44)
                .onVideoChip(OnVideo.disc)
        }.buttonStyle(.plain)
    }
}

/// Emoji going up the right-hand side of the picture.
///
/// Three at a time at the very most, which the conversation enforces rather than this: a friend
/// tapping the row six times should read as enthusiasm, not as weather. Where a reaction starts is
/// the only thing that says whose it is — this viewer's leaves from beside the button they pressed,
/// the other phone's from higher up — which is cheaper than a name beside every one of them.
///
/// With «Уменьшение движения» on, nothing travels: the emoji appears where it would have started
/// and holds there for the same 1.2 seconds. Same information, no movement.
struct TogetherReactionBurst: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let reactions: [TogetherFlyingReaction]
    var body: some View {
        ZStack {
            ForEach(reactions) { reaction in
                Flight(reaction: reaction, still: reduceMotion)
                    .frame(maxWidth: .infinity, maxHeight: .infinity,
                           alignment: reaction.mine ? .bottomTrailing : .trailing)
            }
        }
        .padding(.trailing, 24).padding(.bottom, 96)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
    private struct Flight: View {
        let reaction: TogetherFlyingReaction
        let still: Bool
        @State private var travelled = 0.0
        var body: some View {
            Text(reaction.reaction.symbol)
                .font(.title3)
                .offset(x: 14 * sin(travelled * .pi), y: -120 * travelled)
                // Fades over the back half only: a glyph that starts disappearing the moment it
                // appears is a glyph nobody catches.
                .opacity(min(1, max(0, (1 - travelled) * 2)))
                .onAppear {
                    guard !still else { return }
                    withAnimation(.easeOut(duration: TogetherConversationTiming.reactionLifeMs / 1000)) {
                        travelled = 1
                    }
                }
        }
    }
}

/// Everything said this session, which is the other way to anything the corner has taken away.
///
/// In memory and nowhere else: this is a conversation about one episode, and an app that kept it
/// would have to grow a screen for reading old ones.
struct TogetherHistorySheet: View {
    @Environment(\.dismiss) private var dismiss
    let conversation: TogetherConversation
    var body: some View {
        NavigationStack {
            Group {
                if conversation.history.isEmpty {
                    ContentUnavailableView(TogetherCopy.emptyHistory, systemImage: "bubble.left.and.bubble.right")
                } else {
                    List(conversation.history) { item in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(item.author).font(.caption.weight(.medium))
                                .foregroundStyle(item.mine ? Palette.inkSoft : Palette.accent)
                                .frame(width: 72, alignment: .leading)
                            if let clip = item.clip {
                                Button {
                                    conversation.replay(item.id)
                                } label: {
                                    Label(TogetherCopy.clipLength(clip.durationMs), systemImage: "play.circle")
                                        .monospacedDigit()
                                }
                                .buttonStyle(.plain).foregroundStyle(Palette.ink)
                                .accessibilityLabel(TogetherCopy.replay)
                            } else {
                                Text(item.text ?? "").foregroundStyle(Palette.ink)
                            }
                            Spacer(minLength: 0)
                            Text(item.at, format: .dateTime.hour().minute())
                                .font(.caption).foregroundStyle(Palette.inkSoft).monospacedDigit()
                        }
                    }
                }
            }
            .navigationTitle(TogetherCopy.history)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(TogetherCopy.close) { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}

/// Who is on the other phone, in the row that already carries the dub and the quality: the friend
/// is the same kind of fact about this session — what is currently the case. A session that has
/// ended shows nothing, so the control goes back to being an invitation.
struct TogetherChip: View {
    @Bindable var manager: TogetherManager
    /// «Смотрим «…», 7 серию. Открой в Kaeru: …», or nothing while there is no room to share.
    var invitation: String?
    var body: some View {
        if let label = TogetherCopy.sessionChip(phase: manager.phase, peerName: manager.peerName) {
            Menu {
                if let invitation {
                    ShareLink(item: invitation) { Label(TogetherCopy.share, systemImage: "square.and.arrow.up") }
                }
                Button(TogetherCopy.leave, systemImage: "person.badge.minus", role: .destructive) {
                    Task { await manager.leave() }
                }
            } label: {
                Label(label, systemImage: "person.2.fill")
                    .font(.footnote.weight(.medium)).lineLimit(1)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .onVideoChip(OnVideo.disc)
            }
            .accessibilityLabel("\(TogetherCopy.watchTogether): \(label)")
        }
    }
}
