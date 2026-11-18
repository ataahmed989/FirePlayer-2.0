package app.fireplayer.vod

import android.content.Context

/**
 * Where Films & Shows' pages get their [VodRepository]. The pages only ever ask here ([get]); the data side fills it
 * (Cinemeta, TMDB and the library). Until a repository is set the pages show their "Couldn't load titles" state.
 */
object VodRepositories {
    @Volatile
    private var instance: VodRepository? = null

    /** The data side's repository, made once for the app ([context] is only used to make it). */
    @Volatile
    var factory: ((Context) -> VodRepository)? = null

    /** The app's repository, or null while none is set. Main thread. */
    fun get(context: Context): VodRepository? {
        instance?.let { return it }
        val make = factory ?: return null
        return make(context.applicationContext).also { instance = it }
    }

    /** Sets (or clears) the repository the pages use: the data side, or a test. */
    fun set(repository: VodRepository?) {
        instance = repository
    }
}
