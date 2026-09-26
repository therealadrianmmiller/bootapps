package com.adrianmmiller.bamina

/**
 * Represents one third-party app that has at least one receiver
 * listening for a boot-related broadcast.
 */
data class BootAppInfo(
    val packageName: String,
    val appLabel: String,
    val receiverClasses: List<String>,  // boot-listening receiver components (pkg-relative not needed, fully qualified)
    var receiversEnabled: Boolean,      // true if ANY boot receiver is still enabled (app itself is untouched)
    var isSelected: Boolean = false     // checked in the UI (queued for disable/enable)
)
