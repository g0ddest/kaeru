package app.kaeru.data.viewsync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.kaeru.domain.viewsync.SyncStateStore
import app.kaeru.shared.domain.sync.SyncTitles
import app.kaeru.shared.domain.sync.SyncWire
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * What viewing sync keeps across launches, in the app's preferences.
 *
 * The outbox and the dub stamps are each written with the account they belong to and read as empty
 * under any other, so nothing one account left behind can go out with another's token. The list of
 * seeded accounts is the device's: a sign-out leaves it alone, and the same account signing back in
 * is not sent its whole history again.
 */
@Singleton
class DataStoreSyncState @Inject constructor(
    @param:Named("prefs") private val dataStore: DataStore<Preferences>,
) : SyncStateStore {
    private val outboxKey = stringPreferencesKey("sync_outbox")
    private val outboxAccountKey = longPreferencesKey("sync_outbox_account")
    private val stampsKey = stringPreferencesKey("sync_dub_stamps")
    private val stampsAccountKey = longPreferencesKey("sync_dub_stamps_account")
    private val seededKey = stringSetPreferencesKey("sync_seeded")

    override suspend fun outbox(account: Long): SyncTitles {
        val prefs = dataStore.data.first()
        if (prefs[outboxAccountKey] != account) return emptyMap()
        val raw = prefs[outboxKey] ?: return emptyMap()
        // Written by a build that no longer agrees on the shape: nothing of it can be sent.
        return SyncWire.titles(raw) ?: emptyMap()
    }

    override suspend fun setOutbox(account: Long, titles: SyncTitles) {
        dataStore.edit { prefs ->
            if (titles.isEmpty()) {
                prefs.remove(outboxKey)
                prefs.remove(outboxAccountKey)
            } else {
                prefs[outboxKey] = SyncWire.body(titles)
                prefs[outboxAccountKey] = account
            }
        }
    }

    override suspend fun seeded(): Set<Long> =
        dataStore.data.first()[seededKey].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    override suspend fun markSeeded(account: Long) {
        dataStore.edit { it[seededKey] = it[seededKey].orEmpty() + account.toString() }
    }

    override suspend fun forgetSeeded(account: Long) {
        dataStore.edit { it[seededKey] = it[seededKey].orEmpty() - account.toString() }
    }

    override suspend fun dubStamps(account: Long): Map<Int, Long> {
        val prefs = dataStore.data.first()
        if (prefs[stampsAccountKey] != account) return emptyMap()
        return prefs[stampsKey].orEmpty().split(',').mapNotNull { pair ->
            val parts = pair.split(':')
            val animeId = parts.getOrNull(0)?.toIntOrNull()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && animeId != null && at != null) animeId to at else null
        }.toMap()
    }

    override suspend fun setDubStamps(account: Long, stamps: Map<Int, Long>) {
        dataStore.edit { prefs ->
            prefs[stampsKey] = stamps.entries.joinToString(",") { "${it.key}:${it.value}" }
            prefs[stampsAccountKey] = account
        }
    }
}
