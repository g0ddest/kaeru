package app.kaeru.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.domain.update.UpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The one thing this device remembers about updates: the last check that finished.
 *
 * It is both halves of the feature's memory at once — the timestamp the daily throttle reads, and
 * the answer the screen shows when there is no network to ask again. Keeping them as one record
 * is what makes «проверено вчера, доступна 0.4.0» a single fact rather than two that can disagree.
 *
 * Stored as one JSON string rather than as six preference keys. A result is written and read
 * whole, never field by field, and six keys would be six chances for a half-written record to
 * describe a release that never existed.
 */
@Singleton
class UpdatePreferences @Inject constructor(
    @param:Named("updates") private val dataStore: DataStore<Preferences>,
) {
    private val resultKey = stringPreferencesKey("last_result")
    private val json = Json { ignoreUnknownKeys = true }

    val lastResult: Flow<UpdateResult?> = dataStore.data.map { prefs -> decode(prefs[resultKey]) }

    suspend fun last(): UpdateResult? = lastResult.first()

    suspend fun save(result: UpdateResult) {
        dataStore.edit { it[resultKey] = json.encodeToString(StoredResult.of(result)) }
    }

    /**
     * A record that will not parse is no record.
     *
     * The only way to get one is a build that changed this shape, and the cost of that is one
     * extra check on one launch — which is a far better answer than an exception on the path that
     * builds the home screen.
     */
    private fun decode(raw: String?): UpdateResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString<StoredResult>(raw).toDomain()
        } catch (invalid: SerializationException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }
}

/** The stored shape, kept in the data layer so the domain model owes nothing to a serializer. */
@Serializable
private data class StoredResult(
    val checkedAtMillis: Long,
    val installedVersion: String,
    val release: StoredRelease? = null,
) {
    fun toDomain() = UpdateResult(
        checkedAt = Instant.ofEpochMilli(checkedAtMillis),
        installedVersion = installedVersion,
        release = release?.toDomain(),
    )

    companion object {
        fun of(result: UpdateResult) = StoredResult(
            checkedAtMillis = result.checkedAt.toEpochMilli(),
            installedVersion = result.installedVersion,
            release = result.release?.let(StoredRelease::of),
        )
    }
}

@Serializable
private data class StoredRelease(
    val version: String,
    val publishedAtMillis: Long? = null,
    val notes: String = "",
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
) {
    fun toDomain() = UpdateRelease(
        version = version,
        publishedAt = publishedAtMillis?.let(Instant::ofEpochMilli),
        notes = notes,
        apkUrl = apkUrl,
        apkName = apkName,
        sizeBytes = sizeBytes,
    )

    companion object {
        fun of(release: UpdateRelease) = StoredRelease(
            version = release.version,
            publishedAtMillis = release.publishedAt?.toEpochMilli(),
            notes = release.notes,
            apkUrl = release.apkUrl,
            apkName = release.apkName,
            sizeBytes = release.sizeBytes,
        )
    }
}
