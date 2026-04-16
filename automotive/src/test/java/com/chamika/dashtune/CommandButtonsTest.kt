package com.chamika.dashtune

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CommandButtonsTest {

    private fun playerWith(repeatMode: Int, shuffleEnabled: Boolean): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.repeatMode } returns repeatMode
        every { player.shuffleModeEnabled } returns shuffleEnabled
        return player
    }

    // --- Button list structure tests ---

    @Test
    fun `createButtons returns exactly 3 buttons`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        assertEquals(3, buttons.size)
    }

    @Test
    fun `createButtons returns shuffle then repeat then sync`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        assertEquals("Toggle Shuffle", buttons[0].displayName.toString())
        assertEquals("Toggle repeat", buttons[1].displayName.toString())
        assertEquals("Sync Library", buttons[2].displayName.toString())
    }

    // --- Repeat mode icon tests ---

    @Test
    fun `repeat button uses ICON_REPEAT_OFF when repeat mode is OFF`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val repeatButton = buttons[1]
        assertEquals(CommandButton.ICON_REPEAT_OFF, repeatButton.icon)
    }

    @Test
    fun `repeat button uses ICON_REPEAT_ALL when repeat mode is ALL`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_ALL, false))
        val repeatButton = buttons[1]
        assertEquals(CommandButton.ICON_REPEAT_ALL, repeatButton.icon)
    }

    @Test
    fun `repeat button uses ICON_REPEAT_ONE when repeat mode is ONE`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_ONE, false))
        val repeatButton = buttons[1]
        assertEquals(CommandButton.ICON_REPEAT_ONE, repeatButton.icon)
    }

    @Test(expected = IllegalStateException::class)
    fun `createButtons throws IllegalStateException for invalid repeat mode`() {
        CommandButtons.createButtons(playerWith(99, false))
    }

    // --- Shuffle mode icon tests ---

    @Test
    fun `shuffle button uses ICON_SHUFFLE_OFF when shuffle is disabled`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val shuffleButton = buttons[0]
        assertEquals(CommandButton.ICON_SHUFFLE_OFF, shuffleButton.icon)
    }

    @Test
    fun `shuffle button uses ICON_SHUFFLE_ON when shuffle is enabled`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, true))
        val shuffleButton = buttons[0]
        assertEquals(CommandButton.ICON_SHUFFLE_ON, shuffleButton.icon)
    }

    // --- Sync button tests ---

    @Test
    fun `sync button uses ICON_SYNC`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val syncButton = buttons[2]
        assertEquals(CommandButton.ICON_SYNC, syncButton.icon)
    }

    // --- Session command tests ---

    @Test
    fun `repeat button has REPEAT_COMMAND session command`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val repeatButton = buttons[1]
        assertNotNull(repeatButton.sessionCommand)
        assertEquals(DashTuneSessionCallback.REPEAT_COMMAND, repeatButton.sessionCommand?.customAction)
    }

    @Test
    fun `shuffle button has SHUFFLE_COMMAND session command`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val shuffleButton = buttons[0]
        assertNotNull(shuffleButton.sessionCommand)
        assertEquals(DashTuneSessionCallback.SHUFFLE_COMMAND, shuffleButton.sessionCommand?.customAction)
    }

    @Test
    fun `sync button has SYNC_COMMAND session command`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        val syncButton = buttons[2]
        assertNotNull(syncButton.sessionCommand)
        assertEquals(DashTuneSessionCallback.SYNC_COMMAND, syncButton.sessionCommand?.customAction)
    }

    // --- Slot tests ---

    @Test
    fun `all buttons are assigned to SLOT_OVERFLOW`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_OFF, false))
        buttons.forEach { button ->
            assertTrue(button.slots.contains(CommandButton.SLOT_OVERFLOW))
        }
    }

    // --- Combined state tests ---

    @Test
    fun `createButtons with shuffle on and repeat all produces correct icons`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_ALL, true))
        assertEquals(CommandButton.ICON_SHUFFLE_ON, buttons[0].icon)
        assertEquals(CommandButton.ICON_REPEAT_ALL, buttons[1].icon)
    }

    @Test
    fun `createButtons with shuffle off and repeat one produces correct icons`() {
        val buttons = CommandButtons.createButtons(playerWith(Player.REPEAT_MODE_ONE, false))
        assertEquals(CommandButton.ICON_SHUFFLE_OFF, buttons[0].icon)
        assertEquals(CommandButton.ICON_REPEAT_ONE, buttons[1].icon)
    }
}
