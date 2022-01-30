package de.tap.easy_xkcd.database

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tap.xkcd_reader.BuildConfig
import com.tap.xkcd_reader.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tap.easy_xkcd.GlideApp
import de.tap.easy_xkcd.explainXkcd.ExplainXkcdApi
import de.tap.easy_xkcd.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.util.*
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import javax.inject.Singleton

// If a comic has not been cached yet, the comic will be null here
// The number can then be used to request caching it.
data class ComicContainer(
    val number: Int,
    val comic: Comic?,
    val searchPreview: String = "",
) {
    fun hasComic() = (comic != null)
}

fun Comic.toContainer() = ComicContainer(number, this)

fun Flow<List<Comic>>.mapToComicContainer() = map { list -> list.map { it.toContainer() } }

fun Map<Int, Comic>.mapToComicContainer(size: Int) =
    MutableList(size) { index ->
        ComicContainer(index + 1, this[index + 1])
    }

sealed class ProgressStatus {
    data class SetProgress(val value: Int, val max: Int) : ProgressStatus()
    object ResetProgress : ProgressStatus()
    object Finished : ProgressStatus()
}

interface ComicRepository {
    val comics: Flow<List<ComicContainer>>

    val favorites: Flow<List<ComicContainer>>

    val unreadComics: Flow<List<ComicContainer>>

    val newestComicNumber: Flow<Int>

    val comicCached: SharedFlow<Comic>

    val foundNewComic: Channel<Unit>

    suspend fun cacheComic(number: Int)

    val cacheAllComics: Flow<ProgressStatus>

    suspend fun getUriForSharing(number: Int): Uri?

    suspend fun getRedditThread(comic: Comic): String?

    suspend fun isFavorite(number: Int): Boolean

    @ExperimentalCoroutinesApi
    suspend fun saveOfflineBitmaps(): Flow<ProgressStatus>

    suspend fun removeAllFavorites()

    suspend fun setRead(number: Int, read: Boolean)

    suspend fun setFavorite(number: Int, favorite: Boolean)

    suspend fun setBookmark(number: Int)

    suspend fun migrateRealmDatabase()

    suspend fun oldestUnreadComic(): Comic?

    fun getOfflineUri(number: Int): Uri?

    suspend fun searchComics(query: String) : List<ComicContainer>

    suspend fun getOrCacheTranscript(comic: Comic): String?
}

@Singleton
class ComicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefHelper: PrefHelper,
    private val comicDao: ComicDao,
    private val client: OkHttpClient,
    val coroutineScope: CoroutineScope,
    private val explainXkcdApi: ExplainXkcdApi,
    private val xkcdApi: XkcdApi,
) : ComicRepository {

//    override val comicCached = Channel<Comic>()
    val _comicCached = MutableSharedFlow<Comic>()
    override val comicCached = _comicCached

    override val foundNewComic = Channel<Unit>()

    @ExperimentalCoroutinesApi
    override val newestComicNumber = flow {
        try {
            val newestComic = Comic(xkcdApi.getNewestComic(), context)
            if (newestComic.number != prefHelper.newest) {
                // In offline mode, we need to cache all the new comics here
                if (prefHelper.fullOfflineEnabled()) {
                    val firstComicToCache = prefHelper.newest + 1
                    coroutineScope.launch {
                        (firstComicToCache..newestComic.number).map { number ->
                            downloadComic(number)?.let { comic ->
                                saveOfflineBitmap(number).onCompletion {
                                    comicDao.insert(comic)
                                    _comicCached.emit(comic)
                                }.collect()
                            }
                        }
                    }
                }

                if (prefHelper.newest != 0) {
                    foundNewComic.send(Unit)
                }

                prefHelper.setNewestComic(newestComic.number)
                emit(newestComic.number)
            }
        } catch (e: Exception) {
            Timber.e(e, "While downloading newest comic")
        }
    }.flowOn(Dispatchers.IO).stateIn(coroutineScope, SharingStarted.Lazily, prefHelper.newest)

    override val comics = combine(comicDao.getComics(), newestComicNumber) { comics, newest ->
        comics.mapToComicContainer(newest)
    }

    override val favorites = comicDao.getFavorites().mapToComicContainer()

    override val unreadComics = comics.map {
        it.filter { container -> container.comic == null || !container.comic.read }
    }

    override suspend fun isFavorite(number: Int): Boolean = comicDao.isFavorite(number)

    override suspend fun setRead(number: Int, read: Boolean) {
        if (comicDao.isRead(number) != read) {
            comicDao.setRead(number, read)
        }
    }

    override suspend fun setFavorite(number: Int, favorite: Boolean) {
        if (favorite) saveOfflineBitmap(number).collect {}

        comicDao.setFavorite(number, favorite)
    }

    override suspend fun removeAllFavorites() = comicDao.removeAllFavorites()

    override suspend fun setBookmark(number: Int) {
        prefHelper.bookmark = number
    }

    override suspend fun oldestUnreadComic() = comicDao.oldestUnreadComic()

    override suspend fun migrateRealmDatabase() {
        if (!prefHelper.hasMigratedRealmDatabase() || BuildConfig.DEBUG ) {
            // Needed for fresh install, will initialize the (empty) realm database
            val databaseManager = DatabaseManager(context)

            val migratedComics = copyResultsFromRealm { realm ->
                realm.where(RealmComic::class.java).findAll()
            }.map { realmComic ->
                Comic(realmComic.comicNumber).apply {
                    favorite = realmComic.isFavorite
                    read = realmComic.isRead
                    title = realmComic.title
                    transcript = realmComic.transcript
                    url = realmComic.url
                    altText = realmComic.altText
                }
            }
            Timber.d("Migrating ${migratedComics.size} comics")
            comicDao.insert(migratedComics)
            prefHelper.setHasMigratedRealmDatabase()
        }
    }

    override suspend fun getRedditThread(comic: Comic) = withContext(Dispatchers.IO) {
        try {
            return@withContext "https://www.reddit.com" + client.newCall(
                Request.Builder()
                    .url("https://www.reddit.com/r/xkcd/search.json?q=${comic.title}&restrict_sr=on")
                    .build()
            )
                .execute().body?.let {
                    JSONObject(it.string())
                        .getJSONObject("data")
                        .getJSONArray("children").getJSONObject(0).getJSONObject("data")
                        .getString("permalink")
                }
        } catch (e: Exception) {
            Timber.e(e, "When getting reddit thread for $comic")
            return@withContext null
        }
    }

    override suspend fun getUriForSharing(number: Int): Uri? {
        saveOfflineBitmap(number).collect {}
        return getOfflineUri(number)
    }

    override fun getOfflineUri(number: Int): Uri? {
        return when (number) {
            //Fix for offline users who downloaded the HUGE version of #1826 or #2185
            1826 -> Uri.parse("android.resource://${context.packageName}/${R.mipmap.birdwatching}")
            2185 -> Uri.parse("android.resource://${context.packageName}/${R.mipmap.cumulonimbus_2x}")

            else -> FileProvider.getUriForFile(
                    context,
                    "de.tap.easy_xkcd.fileProvider",
                    getComicImageFile(number)
                )
        }
    }

    private fun getComicImageFile(number: Int) =
        File(prefHelper.getOfflinePath(context), "${number}.png")

    private fun hasDownloadedComicImage(number: Int) = getComicImageFile(number).exists()

    @ExperimentalCoroutinesApi
    fun saveOfflineBitmap(number: Int): Flow<Unit> = callbackFlow {
        val comic = comicDao.getComic(number)
        if (!hasDownloadedComicImage(number) && comic != null) {
            GlideApp.with(context)
                .asBitmap()
                .load(comic.url)
                .into(object: CustomTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        try {
                            val fos = FileOutputStream(getComicImageFile(number))
                            resource.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            fos.flush()
                            fos.close()
                            channel.close()
                        } catch (e: Exception) {
                            Timber.e(e, "While downloading comic $number")
                            channel.close(e)
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) { channel.close() }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        } else {
            channel.close()
        }
        awaitClose()
    }

    @ExperimentalCoroutinesApi
    override suspend fun saveOfflineBitmaps(): Flow<ProgressStatus> = channelFlow {
        var progress = 0
        val max = prefHelper.newest
        (1..prefHelper.newest).map {
            saveOfflineBitmap(it).onCompletion {
                send(ProgressStatus.SetProgress(progress++, max))
            }
        }
            .merge()
            .collect {}
        send(ProgressStatus.Finished)
    }

    private suspend fun downloadComic(number: Int): Comic? {
        return if (number == 404) {
            Comic.makeComic404()
        } else {
            try {
                Comic(xkcdApi.getComic(number), context)
            } catch (e: Exception) {
                Timber.e(e, "Failed to download comic $number")
                null
            }
        }
    }

    @ExperimentalCoroutinesApi
    override val cacheAllComics = flow {
        val allComics = comicDao.getComicsSuspend().toMutableMap()
        var max: Int
        (1..prefHelper.newest)
            .filter { number -> !allComics.containsKey(number) }
            .also { max = it.size }
            .map { number ->
                flow {
                    downloadComic(number)?.let { emit(it) }
                }
            }
            .merge()
            .collectIndexed { index, comic ->
                comicDao.insert(comic)
                allComics[comic.number] = comic
                emit(ProgressStatus.SetProgress(index + 1, max))
            }

        // TODO This should probably be optional. Prompt the user the first time and save it in the settings!
        emit(ProgressStatus.ResetProgress)
        (1..prefHelper.newest)
            .mapNotNull { allComics[it] }
            .filter { comic -> comic.transcript == "" }
            .also {
                max = it.size
                emit(ProgressStatus.SetProgress(0, max))
            }
            .map { flow { emit(getOrCacheTranscript(it)) } }
            .merge()
            .collectIndexed { index, _ ->
                emit(ProgressStatus.SetProgress(index + 1, max))
            }

        emit(ProgressStatus.Finished)
    }

    @ExperimentalCoroutinesApi
    override suspend fun cacheComic(number: Int) {
        val comicInDatabase = comicDao.getComic(number)
        if (comicInDatabase == null) {
            downloadComic(number)?.let { comic ->
                comicDao.insert(comic)
                _comicCached.emit(comic)
            }
        } else {
            // This should only happen when migrating the old realm database, where there might
            // be a race condition between the cache request and the realm comics being
            // inserted into the new database
            _comicCached.emit(comicInDatabase)
        }
    }

    /**
     * Creates a preview for the transcript of comics that contain the query
     * @param query the users's query
     * @param transcript the comic's transcript
     * @return a short preview of the transcript with the query highlighted
     * @note Copied over from the old SearchResultsActivity.java, can probably be
     * optimized/refactored/simplified
     */
    private fun getPreview(query: String, transcript: String): String {
        var transcript = transcript
        return try {
            val firstWord = query.split(" ".toRegex()).toTypedArray()[0].toLowerCase()
            transcript = transcript.replace(".", ". ").replace("?", "? ").replace("]]", " ")
                .replace("[[", " ").replace("{{", " ").replace("}}", " ")
            val words = ArrayList(
                Arrays.asList(
                    *transcript.toLowerCase().split(" ".toRegex()).toTypedArray()
                )
            )
            var i = 0
            var found = false
            while (!found && i < words.size) {
                found =
                    if (query.length < 5) words[i].matches(Regex(".*\\b$firstWord\\b.*")) else words[i].contains(
                        firstWord
                    )
                if (!found) i++
            }
            var start = i - 6
            var end = i + 6
            if (i < 6) start = 0
            if (words.size - i < 6) end = words.size
            val sb = StringBuilder()
            for (s in words.subList(start, end)) {
                sb.append(s)
                sb.append(" ")
            }
            val s = sb.toString()
            "..." + s.replace(query, "<b>$query</b>") + "..."
        } catch (e: PatternSyntaxException) {
            e.printStackTrace()
            " "
        }
    }

    override suspend fun searchComics(query: String) = comicDao.searchComics(query).map { comic ->
        val preview = when {
            comic.title.contains(query) -> {
                comic.number.toString()
            }
            comic.altText.contains(query) -> {
                getPreview(query, comic.altText)
            }
            else -> {
                getPreview(query, comic.transcript)
            }
        }
        ComicContainer(comic.number, comic, preview)
    }

    private fun String.asCleanedTranscript() =
        HtmlCompat.fromHtml(
            this.replace("\n", "<br />")
                .split("<span id=\"Discussion\"")[0],
            HtmlCompat.FROM_HTML_MODE_LEGACY
        ).toString()
            .replace(Regex("""^\s*Transcript\[edit]"""), "")
            .trim()

    private suspend fun getTranscriptFromApi(number: Int) =
        // On Explainxkcd, this includes the dynamic transcript, which is HUGE!
        // So hardcode the short version here.
        if (number == 2131) {
            """[This was an interactive and dynamic comic during April 1st from its release until its completion. But the final and current image, will be the official image to transcribe. But the dynamic part of the comic as well as the "error image" displayed to services that could not render the dynamic comic is also transcribed here below.]\n\n[The final picture shows the winner of the gold medal in the Emojidome bracket tournament, as well as the runner up with the silver medal. There is no text. The winner is the "Space", "Stars" or "Milky Way" emoji, which is shown with a blue band on top of a dark blue band on top of an almost black background, indicating the light band of the Milky Way in the night sky. Stars (in both five point star shape and as dots) in light blue are spread out in all three bands of color. The large gold medal with its red neck string, is floating close to the middle of the picture, lacking any kind of neck in space to tie it around. To the left of the gold medal is the runner up, the brown Hedgehog, with light-brown face. It clutches the smaller silver medal, also with red neck string, which floats out there in space. The hedgehog with medal is depicted small enough to fit inside the neck string on the gold medal.]"""
        } else {
            try {
                withContext(Dispatchers.IO) {
                    explainXkcdApi.getSections(number).execute().body()?.findPageIdOfTranscript()?.let {
                        explainXkcdApi.getSection(number, it).text.asCleanedTranscript()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "While getting transcript for $number from explainxkcd.com")
                null
            }
        }

    override suspend fun getOrCacheTranscript(comic: Comic): String? {
        return if (comic.transcript != "") {
            comic.transcript
        } else {
            try {
                getTranscriptFromApi(comic.number)?.let { transcript ->
                    comic.transcript = transcript
                    comicDao.insert(comic)

                    transcript
                }
            } catch (e: Exception) {
                Timber.e(e, "While getting transcript for comic $comic")
                null
            }
        }
    }
}

@Module
@InstallIn(ViewModelComponent::class)
class ComicRepositoryModule {
    @Provides
    fun provideComicRepository(
        @ApplicationContext context: Context,
        prefHelper: PrefHelper,
        comicDao: ComicDao,
        client: OkHttpClient,
        explainXkcdApi: ExplainXkcdApi,
        xkcdApi: XkcdApi
    ): ComicRepository = ComicRepositoryImpl(context, prefHelper, comicDao, client, CoroutineScope(Dispatchers.Main), explainXkcdApi, xkcdApi)
}