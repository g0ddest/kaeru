import SwiftUI

/// Hold to speak.
///
/// The whole of the gesture is here because the two outcomes are two parts of one movement: lift
/// and it sends, slide left and it is gone with nothing asked. Telegram taught everybody this and
/// it is not worth teaching them something else.
///
/// Thirty seconds is the ceiling, and hitting it **sends** rather than discards. Throwing away
/// half a minute of somebody's speech because they did not watch a counter is the worst thing this
/// button could do, so it does the opposite.
///
/// The red ring is not decoration. In landscape the status bar is hidden, which takes the system's
/// own microphone indicator off the screen, and this is then the only thing that says the
/// microphone is open.
struct VoiceButton: View {
    @State private var recorder = VoiceRecorder()
    var send: (RecordedClip) -> Void
    var denied: () -> Void
    /// The microphone was granted; the audio session or the recorder would not start.
    var failed: () -> Void = {}
    /// Whether the microphone is open, said out loud so the picture can be turned down under it.
    var openChanged: (Bool) -> Void = { _ in }

    @State private var holding = false
    /// Whether a finger is on the button right now.
    ///
    /// Separate from `holding`, and set the instant the touch lands: the microphone is asked for
    /// asynchronously, and a press short enough to end before the permission comes back would
    /// otherwise open a microphone with nobody holding it and no gesture left to close it.
    @State private var fingerDown = false
    @State private var cancelling = false
    @State private var hinting = false
    @State private var levels: [Float] = []
    @State private var tick = 0

    /// How far sideways is «не отправляй».
    private let cancelDistance: CGFloat = 72

    var body: some View {
        // The bar and the hint sit above the button in the stack's own flow now, not floated over
        // it with `.offset` the way an earlier version of this button drew them. An offset view
        // keeps its un-offset frame for every measurement the parent makes, so that `ZStack` still
        // measured itself as wide as the bar even though the bar was pushed up and out of it — and
        // the row this button sits in is a horizontal `ScrollView`, which clips anything drawn
        // outside the frame it measured. That reproduced exactly the bug a phone found: pressing
        // the microphone widened the row with nothing to see in the new space, because the one
        // thing meant to fill it was being drawn somewhere the `ScrollView` had already cut away.
        // Growing upward for real avoids both at once, the way Android's own `Column` already does.
        VStack(alignment: .leading, spacing: 8) {
            if holding { bar }
            else if hinting { hint }
            button
        }
        .onChange(of: recorder.isRecording) { was, now in
            // The recorder closes itself at the ceiling. Noticing that is what sends the clip —
            // the alternative is discarding half a minute because a counter was not being drawn.
            guard was, !now, holding else { return }
            finish(send: true)
        }
        // The screen going away closes the microphone: a recording nobody can see is a recording
        // nobody agreed to.
        .onDisappear { fingerDown = false; if holding { finish(send: false) } }
    }

    private var button: some View {
        Circle()
            .fill(Color.black.opacity(holding ? 0.62 : 0.42))
            .frame(width: 44, height: 44)
            .overlay { Circle().strokeBorder(holding ? Color.red : .white.opacity(0.16), lineWidth: holding ? 2 : 1) }
            .overlay {
                Image(systemName: holding ? "mic.fill" : "mic")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(cancelling ? Color.red : .white)
            }
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !fingerDown { fingerDown = true; begin() }
                        cancelling = value.translation.width < -cancelDistance
                    }
                    .onEnded { _ in
                        fingerDown = false
                        guard holding else { return }
                        finish(send: !cancelling)
                    }
            )
            // One element with a name, rather than a circle and a glyph: a shape with a label on
            // it is not something a screen reader — or a test — can find and press.
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits([.isButton, .startsMediaSession])
            .accessibilityLabel(TogetherCopy.voice)
            .accessibilityHint(TogetherCopy.voiceHint)
    }

    /// What is being said, as it is being said: the levels, the counter, and the way out.
    private var bar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 2) {
                ForEach(Array(levels.enumerated()), id: \.offset) { _, level in
                    Capsule().fill(.white.opacity(0.85))
                        .frame(width: 2, height: max(3, CGFloat(level) * 22))
                }
            }
            .frame(width: CGFloat(VoiceLimits.bars) * 4, height: 22, alignment: .leading)
            Text(TogetherCopy.clipLength(recorder.elapsedMs)).font(.footnote).monospacedDigit()
                .foregroundStyle(recorder.elapsedMs >= VoiceLimits.warningMs ? Color.orange : .white)
            Text(TogetherCopy.voiceCancel).font(.caption)
                .foregroundStyle(cancelling ? Color.red : Color.white.opacity(0.7))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.black.opacity(0.62), in: Capsule())
        .overlay { Capsule().strokeBorder(.white.opacity(0.16), lineWidth: 1) }
        .fixedSize()
        .task(id: tick) {
            while !Task.isCancelled && holding {
                try? await Task.sleep(for: VoiceLimits.levelTick)
                guard !Task.isCancelled else { return }
                levels.append(recorder.level)
                if levels.count > VoiceLimits.bars { levels.removeFirst() }
            }
        }
    }

    /// A press too short to be speech is a tap, and a tap on this button means the person does not
    /// know it is held. Saying so is the only affordance a sighted viewer gets.
    private var hint: some View {
        Text(TogetherCopy.voiceHint).font(.caption).foregroundStyle(.white)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Color.black.opacity(0.62), in: Capsule())
            .fixedSize()
            .task(id: hinting) {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled else { return }
                hinting = false
            }
    }

    private func begin() {
        Task {
            // Asked at the first hold rather than on the way into a session: this is the moment a
            // person can see what it is for. Nothing starts recording on the way back from a
            // refusal, and nothing starts if the finger has already lifted — audio a person did
            // not know had begun is the one thing a microphone must never do.
            let allowed = await recorder.permitted()
            guard fingerDown else { return }
            guard allowed else { denied(); return }
            // Not a refusal: the microphone was granted a line ago. The audio session or the
            // recorder would not start, and «нужен доступ» would send somebody to Settings for
            // nothing. The reason is in the journal.
            guard recorder.start() else { failed(); return }
            guard fingerDown else { recorder.cancel(); return }
            holding = true; cancelling = false; levels = []; tick += 1
            openChanged(true)
        }
    }

    private func finish(send wanted: Bool) {
        let clip = wanted ? recorder.stop() : nil
        if !wanted { recorder.cancel() }
        holding = false; cancelling = false; levels = []
        openChanged(false)
        if let clip { send(clip) }
        else if wanted { hinting = true }
    }
}
