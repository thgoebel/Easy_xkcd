package de.tap.easy_xkcd.widget

import android.appwidget.AppWidgetProvider
import de.tap.easy_xkcd.utils.PrefHelper
import android.appwidget.AppWidgetManager
import android.widget.RemoteViews
import com.tap.xkcd_reader.R
import android.content.Intent
import de.tap.easy_xkcd.widget.WidgetRandomProvider
import android.app.PendingIntent
import android.content.Context
import de.tap.easy_xkcd.database.RealmComic
import de.tap.easy_xkcd.database.DatabaseManager
import com.bumptech.glide.request.target.AppWidgetTarget
import android.graphics.Bitmap
import android.view.View
import com.bumptech.glide.request.transition.Transition
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import de.tap.easy_xkcd.GlideApp
import de.tap.easy_xkcd.database.comics.ComicDao
import de.tap.easy_xkcd.database.comics.ComicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.IllegalArgumentException
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class WidgetRandomProvider : AppWidgetProvider() {
    @Inject
    lateinit var prefHelper: PrefHelper

    @Inject
    lateinit var repository: ComicRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_random_layout)
        remoteViews.setImageViewBitmap(R.id.ivComic, null)

        CoroutineScope(Dispatchers.Main).launch {
            repository.cacheComic(Random.nextInt(1, prefHelper.newest))?.let { randomComic ->

                GlideApp.with(context)
                    .asBitmap()
                    .load(if (prefHelper.fullOfflineEnabled()) repository.getOfflineUri(randomComic.number) else randomComic.url)
                    .into(
                        object :
                            AppWidgetTarget(context, R.id.ivComic, remoteViews, *appWidgetIds) {
                            override fun onResourceReady(
                                resource: Bitmap,
                                transition: Transition<in Bitmap?>?
                            ) {
                                try {
                                    super.onResourceReady(resource, transition)
                                } catch (e: IllegalArgumentException) {
                                    Timber.e(e, "Loading image failed for ${randomComic.number}")
                                }
                            }
                        }
                    )

                remoteViews.setOnClickPendingIntent(
                    R.id.tvAlt,
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, WidgetRandomProvider::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                remoteViews.setOnClickPendingIntent(
                    R.id.ivComic, PendingIntent.getActivity(
                        context, 1,
                        Intent("de.tap.easy_xkcd.ACTION_COMIC").apply {
                            putExtra("number", randomComic.number)
                            // We might have found a new comic, so make sure main app is updated
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                val titlePrefix =
                    if (prefHelper.widgetShowComicNumber()) "${randomComic.number}: " else ""
                remoteViews.setTextViewText(R.id.tvTitle, "${titlePrefix}${randomComic.title}")

                if (prefHelper.widgetShowAlt()) {
                    remoteViews.setViewVisibility(
                        R.id.tvAlt,
                        View.VISIBLE
                    )
                    remoteViews.setTextViewText(R.id.tvAlt, randomComic.altText)
                }

                appWidgetManager.updateAppWidget(appWidgetIds, remoteViews)
            }
        }
    }
}