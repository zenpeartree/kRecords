package com.zenpeartree.krecords

object RideDebugStore {
    private val listeners = linkedSetOf<(RideDebugSnapshot) -> Unit>()
    private var snapshot = RideDebugSnapshot()

    @Synchronized
    fun current(): RideDebugSnapshot = snapshot

    @Synchronized
    fun update(transform: (RideDebugSnapshot) -> RideDebugSnapshot) {
        snapshot = transform(snapshot)
        val updated = snapshot
        listeners.toList().forEach { listener -> listener(updated) }
    }

    @Synchronized
    fun addListener(listener: (RideDebugSnapshot) -> Unit): RideDebugSnapshot {
        listeners += listener
        return snapshot
    }

    @Synchronized
    fun removeListener(listener: (RideDebugSnapshot) -> Unit) {
        listeners -= listener
    }
}
