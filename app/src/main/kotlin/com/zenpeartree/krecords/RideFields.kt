package com.zenpeartree.krecords

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig

data class FieldDisplay(
    val title: String,
    val value: String,
    val detail: String,
)

abstract class BaseRideField(typeId: String) :
    DataTypeImpl(KarooRecordsExtension.EXTENSION_ID, typeId) {

    final override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(snapshotToStreamState(RideDebugStore.current()))
        val listener: (RideDebugSnapshot) -> Unit = { snapshot ->
            emitter.onNext(snapshotToStreamState(snapshot))
        }
        RideDebugStore.addListener(listener)
        emitter.setCancellable {
            RideDebugStore.removeListener(listener)
        }
    }

    final override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(true, null))
        val listener: (RideDebugSnapshot) -> Unit = { snapshot ->
            emitter.updateView(renderView(context, buildDisplay(snapshot)))
        }
        emitter.updateView(renderView(context, buildDisplay(RideDebugStore.addListener(listener))))
        emitter.setCancellable {
            RideDebugStore.removeListener(listener)
        }
    }

    private fun renderView(context: Context, display: FieldDisplay): RemoteViews {
        return RemoteViews(context.packageName, R.layout.ride_action_field).apply {
            setTextViewText(R.id.ride_field_title, display.title)
            setTextViewText(R.id.ride_field_value, display.value)
            setTextViewText(R.id.ride_field_subtitle, display.detail)
            setOnClickPendingIntent(
                R.id.ride_field_root,
                PendingIntent.getActivity(
                    context,
                    dataTypeId.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
    }

    private fun snapshotToStreamState(snapshot: RideDebugSnapshot): StreamState {
        return StreamState.Streaming(
            DataPoint(
                dataTypeId,
                mapOf(DataType.Field.SINGLE to streamValue(snapshot)),
                streamLabel(snapshot),
            )
        )
    }

    protected abstract fun buildDisplay(snapshot: RideDebugSnapshot): FieldDisplay
    protected abstract fun streamValue(snapshot: RideDebugSnapshot): Double
    protected abstract fun streamLabel(snapshot: RideDebugSnapshot): String
}

class NearbySegmentsField : BaseRideField(FIELD_ID) {
    override fun buildDisplay(snapshot: RideDebugSnapshot): FieldDisplay {
        return when {
            !snapshot.configured -> FieldDisplay("Nearby", "Setup", "Open kRecords")
            !snapshot.authenticated -> FieldDisplay("Nearby", "Auth", "Connect Strava")
            !snapshot.rideActive -> FieldDisplay("Nearby", "${snapshot.cachedSegmentCount}", "cached before ride")
            else -> FieldDisplay(
                "Nearby",
                snapshot.activeSegmentCount.toString(),
                "${snapshot.nearbyTileCount} tiles, ${snapshot.staleTileCount} stale",
            )
        }
    }

    override fun streamValue(snapshot: RideDebugSnapshot): Double = snapshot.activeSegmentCount.toDouble()

    override fun streamLabel(snapshot: RideDebugSnapshot): String = "${snapshot.activeSegmentCount} active"

    companion object {
        const val FIELD_ID = "nearby-segments"
    }
}

class RideStatusField : BaseRideField(FIELD_ID) {
    override fun buildDisplay(snapshot: RideDebugSnapshot): FieldDisplay {
        val value = when {
            !snapshot.configured -> "Setup"
            !snapshot.authenticated -> "Auth"
            snapshot.lastPrName != null -> "PR!"
            snapshot.rideActive -> "Live"
            else -> "Ready"
        }
        val detail = when {
            !snapshot.configured -> "Backend missing"
            !snapshot.authenticated -> "Finish phone login"
            snapshot.lastPrName != null -> snapshot.lastPrName
            snapshot.rideActive -> "loc ${snapshot.locationUpdates} • tiles ${snapshot.lastHydratedTileCount}"
            else -> snapshot.lastMessage
        }
        return FieldDisplay("kRecords", value, detail)
    }

    override fun streamValue(snapshot: RideDebugSnapshot): Double {
        return when {
            !snapshot.configured -> -2.0
            !snapshot.authenticated -> -1.0
            snapshot.lastPrName != null -> 2.0
            snapshot.rideActive -> 1.0
            else -> 0.0
        }
    }

    override fun streamLabel(snapshot: RideDebugSnapshot): String = snapshot.lastMessage

    companion object {
        const val FIELD_ID = "records-status-field"
    }
}
