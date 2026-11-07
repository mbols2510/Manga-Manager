package com.mbols2510.mangamanager.notify

import com.mbols2510.mangamanager.data.db.UpdateEntity

/** Everything the background check may show the user. Implementations never throw. */
interface Notifier {
    /**
     * Whether a notification can be shown at all: the POST_NOTIFICATIONS permission is granted (Android 13+) and
     * notifications are enabled for the app. Task 12 uses it for the in-app banner; nothing here asks for permission.
     */
    fun canNotify(): Boolean

    /**
     * Posts ONE notification summarizing [items] (never one per item, never a cover image). Returns false when
     * nothing was posted because notifications are unavailable (permission denied, notifications or the channel
     * turned off) or [items] is empty.
     */
    fun notifyUpdates(items: List<UpdateEntity>): Boolean

    /** Posts the single "Re-pair this device" notification. Returns false when notifications are unavailable. */
    fun notifyRepairNeeded(): Boolean
}
