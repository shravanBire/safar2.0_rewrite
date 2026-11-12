package com.example.safar20.ui.components

/**
 * Single-place State Message Catalog (P2-U4).
 * Maps every ConnectionState + network availability to plain-language rider messages
 * and optional actions. Never exposes raw exception class names or stack traces.
 */
object StateMessageCatalog {

    enum class BannerAction {
        PAIR_TRACKER,
        OPEN_ADVANCED_SETTINGS,
        RETRY_CONNECTION,
        NAVIGATE_TO_BIKE
    }

    data class StateEntry(
        val pillLabel: String,
        val message: String,
        val primaryActionLabel: String? = null,
        val primaryAction: BannerAction? = null,
        val secondaryActionLabel: String? = null,
        val secondaryAction: BannerAction? = null,
        val showBannerOnHome: Boolean = false,
        val isError: Boolean = false
    )

    fun resolve(
        connectionState: ConnectionState,
        isNetworkAvailable: Boolean = true
    ): StateEntry {
        if (!isNetworkAvailable) {
            return StateEntry(
                pillLabel = "● WAITING FOR INTERNET",
                message = "Waiting for internet",
                showBannerOnHome = true,
                isError = false
            )
        }

        return when (connectionState) {
            is ConnectionState.NO_DEVICE -> StateEntry(
                pillLabel = "● NO TRACKER",
                message = "Pair your tracker to start",
                primaryActionLabel = "PAIR",
                primaryAction = BannerAction.PAIR_TRACKER,
                showBannerOnHome = true
            )
            is ConnectionState.BROKER_NOT_CONFIGURED -> StateEntry(
                pillLabel = "● SETUP INCOMPLETE",
                message = "Setup incomplete",
                primaryActionLabel = "Open Advanced",
                primaryAction = BannerAction.OPEN_ADVANCED_SETTINGS,
                showBannerOnHome = true
            )
            is ConnectionState.CONNECTING -> StateEntry(
                pillLabel = "● CONNECTING…",
                message = "Connecting…",
                showBannerOnHome = false
            )
            is ConnectionState.BROKER_ERROR -> {
                val plainReason = sanitizeReason(connectionState.message)
                StateEntry(
                    pillLabel = "● RELAY UNREACHABLE",
                    message = "Can't reach the relay: $plainReason",
                    primaryActionLabel = "Retry",
                    primaryAction = BannerAction.RETRY_CONNECTION,
                    secondaryActionLabel = "Check settings",
                    secondaryAction = BannerAction.OPEN_ADVANCED_SETTINGS,
                    showBannerOnHome = true,
                    isError = true
                )
            }
            is ConnectionState.WAITING_FOR_TRACKER -> StateEntry(
                pillLabel = "● WAITING FOR TRACKER",
                message = "Tracker isn't responding. Is it powered and online?",
                primaryActionLabel = "Retry",
                primaryAction = BannerAction.RETRY_CONNECTION,
                showBannerOnHome = true
            )
            is ConnectionState.LIVE_NO_FIX -> StateEntry(
                pillLabel = "● LIVE · NO GPS FIX",
                message = "Tracker is searching for satellites. Move to open sky.",
                showBannerOnHome = true
            )
            is ConnectionState.LIVE -> StateEntry(
                pillLabel = "● LIVE",
                message = "Live",
                showBannerOnHome = false
            )
            is ConnectionState.SYNCING -> StateEntry(
                pillLabel = "● SYNCING RIDES… ${connectionState.progressPercent}%",
                message = "Syncing rides… ${connectionState.progressPercent}%",
                showBannerOnHome = true
            )
            is ConnectionState.PARKED -> {
                val ago = formatTimeAgo(connectionState.lastSeenTimestamp)
                val msg = if (ago != null) "Parked · last seen $ago" else "Parked"
                val pill = if (ago != null) "● PARKED · $ago" else "● PARKED"
                StateEntry(
                    pillLabel = pill,
                    message = msg,
                    primaryActionLabel = "Navigate to bike",
                    primaryAction = BannerAction.NAVIGATE_TO_BIKE,
                    showBannerOnHome = false
                )
            }
        }
    }

    fun sanitizeReason(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return "connection interrupted"
        return when {
            clean.contains("Unknown host", ignoreCase = true) ||
                    clean.contains("internet", ignoreCase = true) -> "no internet or unknown host"
            clean.contains("credentials", ignoreCase = true) ||
                    clean.contains("unauthorized", ignoreCase = true) -> "check username or password"
            clean.contains("timed out", ignoreCase = true) -> "connection timed out"
            clean.contains("certificate", ignoreCase = true) ||
                    clean.contains("SSL", ignoreCase = true) -> "secure TLS handshake failed"
            clean.contains("Invalid", ignoreCase = true) -> "invalid relay address"
            else -> "temporarily unavailable"
        }
    }

    fun formatTimeAgo(timestampSeconds: Long?, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): String? {
        if (timestampSeconds == null || timestampSeconds <= 0L) return null
        val diffSec = (nowEpochSeconds - timestampSeconds).coerceAtLeast(0L)
        if (diffSec < 60) return "just now"
        val minutes = diffSec / 60
        if (minutes < 60) return "$minutes min ago"
        val hours = minutes / 60
        if (hours < 24) return "$hours hr ago"
        val days = hours / 24
        return "$days d ago"
    }
}
