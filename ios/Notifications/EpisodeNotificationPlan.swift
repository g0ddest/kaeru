import Foundation

/// What a notification this app posted is called.
///
/// Two prefixes survive a build that no longer posts by announced date: this one names the
/// reminders older builds scheduled from `nextEpisodeAt` alone, and it is still read — by
/// `clearForPlayback` and by the sweep that takes this app's own notifications away — so that a
/// phone upgraded rather than installed does not keep announcing episodes on a rule this app has
/// given up. Nothing writes it any more.
enum EpisodeNotificationPlan {
    static let prefix = "kaeru.episode."
}
