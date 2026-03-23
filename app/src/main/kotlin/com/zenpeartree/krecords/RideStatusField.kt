package com.zenpeartree.krecords

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig

class RideStatusField : DataTypeImpl(KarooRecordsExtension.EXTENSION_ID, FIELD_ID) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val settings = SettingsStore(context)
        val summary = settings.dashboard(SegmentDatabase(context).countSegments())
        val subtitle = when {
            !summary.configured -> "Add backend URL"
            !summary.authenticated -> "Connect Strava"
            summary.lastPrName != null -> "Last PR: ${summary.lastPrName}"
            else -> "${summary.segmentCount} cached segments"
        }

        emitter.updateView(
            RemoteViews(context.packageName, R.layout.ride_action_field).apply {
                setTextViewText(R.id.ride_field_title, context.getString(R.string.ride_field_status_title))
                setTextViewText(R.id.ride_field_subtitle, subtitle)
                setOnClickPendingIntent(
                    R.id.ride_field_root,
                    PendingIntent.getActivity(
                        context,
                        FIELD_ID.hashCode(),
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        )
    }

    companion object {
        const val FIELD_ID = "records-status-field"
    }
}
