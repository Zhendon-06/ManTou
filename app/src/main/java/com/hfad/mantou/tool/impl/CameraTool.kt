package com.hfad.mantou.tool.impl

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "camera",
    description = "唤起系统相机/录像/视频应用",
    usageScenario = "网页里需要让用户拍一张照片、录制一段视频，或直接打开系统相机"
)
class CameraTool(context: Context) : BaseTool(context) {

    // 注：拿到拍照结果（Bitmap / 文件 URI）需要 onActivityResult，
    // 当前 JS 桥是同步调用，没法把异步结果传回 JS。这里先实现「唤起系统相机」，
    // 用户拍完照系统会自动保存到相册，网页侧可以引导用户从相册选择上传。
    // 后续可以扩展一个基于 evaluateJavascript 的异步回调机制。

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统相机进入拍照模式。用户拍完照自动保存到系统相册。",
        example = "window.MantouApp.camera.cameraTakePhoto();"
    )
    @ToolReturns(
        description = "是否成功唤起相机",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun cameraTakePhoto(): String {
        return launchIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "找不到系统相机")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统相机进入录像模式。用户录完自动保存到系统相册。",
        example = "window.MantouApp.camera.cameraRecordVideo();"
    )
    @ToolReturns(
        description = "是否成功唤起录像",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun cameraRecordVideo(): String {
        return launchIntent(Intent(MediaStore.ACTION_VIDEO_CAPTURE), "找不到系统相机")
    }

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统相机应用（默认界面，不强制拍照或录像）。",
        example = "window.MantouApp.camera.cameraOpen();"
    )
    @ToolReturns(
        description = "是否成功打开相机",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true}, \"error\": null}"
    )
    fun cameraOpen(): String {
        // ACTION_STILL_IMAGE_CAMERA 是「打开相机 App 自己」，不带拍照流程
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return launchIntent(intent, "找不到系统相机")
    }
}
