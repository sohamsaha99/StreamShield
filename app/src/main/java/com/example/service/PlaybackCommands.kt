package com.example.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class PlaybackCommand { PLAY, PAUSE, TOGGLE, STOP, NEXT, PREVIOUS }

/**
 * Process-wide channel for media controls that do not originate in the UI (notification buttons,
 * lock screen, headset, PiP window). The browser controller turns them into page commands.
 */
object PlaybackCommands {
    private val commands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 8)
    val flow: SharedFlow<PlaybackCommand> = commands.asSharedFlow()

    fun send(command: PlaybackCommand) {
        commands.tryEmit(command)
    }
}
