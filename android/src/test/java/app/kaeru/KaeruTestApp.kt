package app.kaeru

import android.app.Application

/**
 * The application Robolectric puts under every unit test in this module.
 *
 * Deliberately not [KaeruApp]. The real one builds the Hilt graph in `onCreate` and starts the
 * download engine, which opens a media3 database, a `SimpleCache` over a real directory, and a
 * `DownloadManager` whose `RequirementsWatcher` registers a broadcast receiver and a network
 * callback. Every test in this module paid for all of it, and none of them asked for any of it.
 *
 * Two ways that showed. `AndroidConnectivityTest` counts network callbacks and saw the watcher's
 * as one of its own. And once the engine's start moved off the calling thread — where it belongs,
 * because in the app that thread is the main one — the work could land after Robolectric had taken
 * the environment down, and fail out of a shadow with `activityThread is null`, which `runTest`
 * then reported against whichever test started next.
 *
 * Nothing here needs the graph: these tests build what they are about by hand, and no test in the
 * module uses Hilt. So the application under them is an application and nothing more.
 */
class KaeruTestApp : Application()
