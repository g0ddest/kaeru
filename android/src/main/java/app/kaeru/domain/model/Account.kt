package app.kaeru.domain.model

/**
 * Whoever is signed in, as much of them as the app has any use for: a name to show and a face
 * beside it.
 *
 * The id is Shikimori's and is the same number every account-owned row in the cache is keyed by,
 * so a screen holding one of these can say which account it is looking at rather than assuming
 * there is only ever one.
 */
data class Account(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
)
