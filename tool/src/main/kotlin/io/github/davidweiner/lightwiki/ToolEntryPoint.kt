package io.github.davidweiner.lightwiki

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    // Wiki has no server of its own and doesn't use push notifications.
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
