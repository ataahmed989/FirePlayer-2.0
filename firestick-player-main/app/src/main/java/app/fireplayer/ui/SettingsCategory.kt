package app.fireplayer.ui

/**
 * App settings' categories (user approved), in the order the Settings tab lists them, with the settings each holds by
 * the labels of their rows (the options under a row go with it). Every setting is in exactly one category. The rows
 * are still drawn by one function each ([SettingsPanels.renderCategory]; Playlists & EPG and Live TV by Live TV's own
 * windows), so a setting reads and writes its one saved value wherever it shows.
 */
enum class SettingsCategory(val title: String, val settings: List<String>) {
    PLAYLISTS_EPG(
        "Playlists & EPG",
        listOf(
            "Manage playlists", "Add playlist", "Reorder playlists", "Update playlists",
            "EPG sources", "Manage EPG sources", "Days to keep EPG", "Update interval", "Update EPG", "Clear EPG",
        ),
    ),
    // Live TV buffer is in Playback's window too (user): the same row, one saved value.
    LIVE_TV(
        "Live TV",
        listOf("Channel changing buttons", "Hide controls after", "Full screen after no key presses", "Live TV buffer"),
    ),
    PLAYBACK(
        "Playback",
        listOf(
            "Play next episode", "Resume where I left off", "Match frame rate", "Surround passthrough",
            "Skip buttons", "Next episode / Exit countdown",
        ),
    ),
    REMOTE(
        "Remote & skipping",
        listOf(
            "Menu button opens", "D-pad left / right", "Rewind / fast-forward", "Hold to speed up",
            "Preview while skipping",
        ),
    ),
    SUBTITLES_AUDIO(
        "Subtitles & audio",
        listOf("Subtitle language", "Audio language", "Auto sync subtitles", "Subtitle style", "Font from your phone"),
    ),
    LOOK("Look", listOf("Glass look", "Glass refresh", "Info pods")),
    ACCOUNTS(
        "Accounts & services",
        listOf(
            "OpenSubtitles API key", "OpenSubtitles username", "OpenSubtitles password", "TMDB API key",
            "Check the keys now",
        ),
    ),
    TITLES("TiviMate titles", listOf("English titles in TiviMate", "Check playlists now")),
    APP("App", listOf("Check for updates")),
    ;

    /** Drawn by Live TV's own windows (LiveSettings), which only a screen with them can open. */
    val isLive: Boolean get() = this == PLAYLISTS_EPG || this == LIVE_TV

    companion object {
        /** The Settings tab's last row, after the categories: a tool rather than settings. */
        const val OPEN_A_LINK = "Open a link"

        /** The window listing the categories (Menu, and App › App settings in the player windows). */
        const val LIST_TITLE = "App settings"

        /**
         * How many columns the Settings tab needs for [rows] rows, each [rowDp] tall plus [gapDp] under it, below a
         * title [titleDp] tall (with its margin), on a screen [screenDp] tall that keeps [edgeDp] clear above and
         * below (the part a TV may cut off): one while they all fit, otherwise as few as do.
         */
        fun columnsFor(rows: Int, screenDp: Int, rowDp: Int, gapDp: Int, titleDp: Int, edgeDp: Int): Int {
            if (rows <= 0) return 1
            val room = screenDp - 2 * edgeDp - titleDp
            val perColumn = (room / (rowDp + gapDp)).coerceAtLeast(1)
            return (rows + perColumn - 1) / perColumn
        }

        /** [items] in [columns] columns, filled top to bottom and then left to right, the first ones the fullest. */
        fun <T> inColumns(items: List<T>, columns: Int): List<List<T>> {
            if (items.isEmpty()) return emptyList()
            val perColumn = (items.size + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)
            return items.chunked(perColumn)
        }
    }
}
