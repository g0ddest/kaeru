package app.kaeru.data.auth

import app.kaeru.data.library.AppPreferences
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.domain.model.Account
import app.kaeru.domain.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in account, read from the preference store and topped up from `whoami`.
 *
 * Sign-in already calls `whoami` to verify the identity, so in the ordinary case the nickname is
 * written down before any screen asks for it. [refresh] exists for the two cases that leaves: an
 * install that predates this screen, and a nickname or avatar changed on Shikimori since.
 */
@Singleton
class PreferencesAccountRepository @Inject constructor(
    private val prefs: AppPreferences,
    private val api: ShikimoriApi,
) : AccountRepository {

    override val account: Flow<Account?> = prefs.account

    /**
     * Succeeds when Shikimori answered, whether or not that answer changed anything: an answer
     * about a different account is a correct answer to a question this device no longer has, and
     * writing it down would label one account with another's nickname after a sign-out raced the
     * call.
     */
    override suspend fun refresh(): Result<Unit> = try {
        val user = api.whoami()
        if (user.id == prefs.userId()) prefs.setAccountProfile(user.nickname, user.avatar)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toDomainFailure())
    }
}
