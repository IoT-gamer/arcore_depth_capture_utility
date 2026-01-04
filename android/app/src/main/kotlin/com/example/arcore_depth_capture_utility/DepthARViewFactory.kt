package com.example.arcore_depth_capture_utility

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class DepthViewFactory(
    private val activity: Activity,
    private val messenger: BinaryMessenger,
    private val lifecycle: Lifecycle
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return DepthView(activity, context, messenger, viewId, lifecycle)
    }
}