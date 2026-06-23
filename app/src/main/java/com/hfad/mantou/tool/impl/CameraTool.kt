package com.hfad.mantou.tool.impl

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "camera",
    description = "唤起系统相机拍照/录像，并可把拍到的照片回传给网页显示",
    usageScenario = "网页里需要让用户拍一张照片并显示在 HTML 页面中、录制一段视频，或直接打开系统相机"
)
class CameraTool(context: Context) : BaseTool(context) {

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统相机拍照，拍完后会调用 window.MantouApp.onCameraPhoto(dataUrl, uri)；dataUrl 可直接赋给 img.src 在 HTML 页面显示。",
        example = "window.MantouApp.onCameraPhoto = function(dataUrl){ document.querySelector('#preview').src = dataUrl; }; window.MantouApp.camera.cameraTakePhoto();"
    )
    @ToolReturns(
        description = "是否成功发起拍照；照片结果通过 JS 回调异步返回",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true, \"callback\": \"window.MantouApp.onCameraPhoto\"}, \"error\": null}"
    )
    fun cameraTakePhoto(): String {
        return cameraTakePhotoWithCallback(DEFAULT_PHOTO_CALLBACK)
    }

    @JavascriptInterface
    @ToolMethod(
        description = "打开系统相机拍照，拍完后调用指定 JS 回调。回调函数接收两个参数：dataUrl 和 uri；dataUrl 可直接赋给 img.src。",
        example = "window.handlePhoto = function(dataUrl, uri){ document.getElementById('preview').src = dataUrl; }; window.MantouApp.camera.cameraTakePhotoWithCallback('window.handlePhoto');"
    )
    @ToolReturns(
        description = "是否成功发起拍照；照片结果通过 callbackName 指向的 JS 函数异步返回",
        jsonExample = "{\"success\": true, \"data\": {\"launched\": true, \"callback\": \"window.handlePhoto\"}, \"error\": null}"
    )
    fun cameraTakePhotoWithCallback(
        @ToolParam(name = "callbackName", description = "全局 JS 回调函数名，例如 window.handlePhoto；为空则使用 window.MantouApp.onCameraPhoto") callbackName: String
    ): String {
        val normalizedCallback = callbackName.trim().ifBlank { DEFAULT_PHOTO_CALLBACK }
        return if (CameraPhotoBridge.requestPhoto(normalizedCallback)) {
            success("launched" to true, "callback" to normalizedCallback)
        } else {
            error("当前页面不支持接收拍照结果")
        }
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

    private companion object {
        private const val DEFAULT_PHOTO_CALLBACK = "window.MantouApp.onCameraPhoto"
    }
}
