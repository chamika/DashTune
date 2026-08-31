package com.chamika.dashtune

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.chamika.dashtune.DashTuneSessionCallback.Companion.DOWNLOAD_COMMAND
import com.chamika.dashtune.DashTuneSessionCallback.Companion.REMOVE_DOWNLOAD_COMMAND
import com.chamika.dashtune.DashTuneSessionCallback.Companion.REPEAT_COMMAND
import com.chamika.dashtune.DashTuneSessionCallback.Companion.SHUFFLE_COMMAND
import com.chamika.dashtune.DashTuneSessionCallback.Companion.SYNC_COMMAND
import com.google.common.collect.ImmutableList

object CommandButtons {

    /**
     * Per-media-item browse actions (AAOS row overflow). A browse item opts into one of these by
     * listing the matching command action in its `MediaMetadata.supportedCommands`; the tap is
     * delivered to [DashTuneSessionCallback.onCustomCommand] with the item id in the args bundle
     * under `MediaConstants.EXTRA_KEY_MEDIA_ID`.
     *
     * Each button MUST carry an icon [Uri] as well as the res id. `setCustomIconResId` is only
     * understood by Media3 hosts; `LegacyConversions.convertToBundle` drops the browse action's
     * icon-uri key entirely when `iconUri` is null, and the AAOS media host then calls
     * `Uri.parse(null)` in `MediaItemsRepository.parseBrowseActions` and crashes on connect.
     */
    @OptIn(UnstableApi::class)
    fun mediaItemButtons(context: Context): ImmutableList<CommandButton> {
        val download = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(R.string.download_for_offline))
            .setCustomIconResId(R.drawable.ic_download)
            .setIconUri(resourceUri(context, R.drawable.ic_download))
            .setSessionCommand(SessionCommand(DOWNLOAD_COMMAND, Bundle.EMPTY))
            .build()

        val remove = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(R.string.remove_download))
            .setCustomIconResId(R.drawable.ic_download_remove)
            .setIconUri(resourceUri(context, R.drawable.ic_download_remove))
            .setSessionCommand(SessionCommand(REMOVE_DOWNLOAD_COMMAND, Bundle.EMPTY))
            .build()

        return ImmutableList.of(download, remove)
    }

    /** `android.resource://` uri for [resId], resolvable by the media host across processes. */
    fun resourceUri(context: Context, resId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(context.resources.getResourceTypeName(resId))
        .appendPath(context.resources.getResourceEntryName(resId))
        .build()

    @OptIn(UnstableApi::class)
    fun createButtons(player: Player): List<CommandButton> {
        val repeatIcon = when (player.repeatMode) {
            REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            REPEAT_MODE_OFF -> CommandButton.ICON_REPEAT_OFF
            REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            else -> throw IllegalStateException("Unexpected change to Repeat mode")
        }

        val repeat =
            CommandButton.Builder(repeatIcon)
                .setDisplayName("Toggle repeat")
                .setSessionCommand(SessionCommand(REPEAT_COMMAND, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()

        val shuffleIcon =
            if (player.shuffleModeEnabled)
                CommandButton.ICON_SHUFFLE_ON
            else
                CommandButton.ICON_SHUFFLE_OFF

        val shuffle = CommandButton.Builder(shuffleIcon)
            .setDisplayName("Toggle Shuffle")
            .setSessionCommand(SessionCommand(SHUFFLE_COMMAND, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val sync = CommandButton.Builder(CommandButton.ICON_SYNC)
            .setDisplayName("Sync Library")
            .setSessionCommand(SessionCommand(SYNC_COMMAND, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        return ImmutableList.of(shuffle, repeat, sync)
    }
}
