package com.hfad.mantou.tool.impl

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.webkit.JavascriptInterface
import com.hfad.mantou.tool.BaseTool
import com.hfad.mantou.tool.MantouTool
import com.hfad.mantou.tool.ToolMethod
import com.hfad.mantou.tool.ToolParam
import com.hfad.mantou.tool.ToolReturns

@MantouTool(
    name = "flashlight",
    description = "控制设备的闪光灯/手电筒",
    usageScenario = "应急照明、SOS 闪烁、亮度提示等"
)
class FlashlightTool(context: Context) : BaseTool(context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // 当前手电筒状态。CameraManager 没有同步读取接口，自己跟踪一份。
    // 注意：如果用户用其他 App 改了状态，这里会不同步——是已知限制。
    @Volatile
    private var torchOn: Boolean = false

    // 带闪光灯的摄像头 ID，懒加载。一般是后置摄像头。
    private val torchCameraId: String? by lazy { findTorchCameraId() }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) torchOn = enabled
        }
    }

    init {
        // 让我们的 torchOn 状态尽量贴合系统真实状态。
        runCatching { cameraManager.registerTorchCallback(torchCallback, null) }
    }

    @JavascriptInterface
    @ToolMethod(
        description = "打开手电筒。设备无闪光灯时返回错误。",
        example = "window.MantouApp.flashlight.flashlightOn();"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"on\": true}, \"error\": null}"
    )
    fun flashlightOn(): String = setTorch(true)

    @JavascriptInterface
    @ToolMethod(
        description = "关闭手电筒。",
        example = "window.MantouApp.flashlight.flashlightOff();"
    )
    @ToolReturns(
        description = "操作是否成功",
        jsonExample = "{\"success\": true, \"data\": {\"on\": false}, \"error\": null}"
    )
    fun flashlightOff(): String = setTorch(false)

    @JavascriptInterface
    @ToolMethod(
        description = "切换手电筒开关，返回切换后状态。",
        example = "var r = JSON.parse(window.MantouApp.flashlight.flashlightToggle()); if (r.success) console.log('on=' + r.data.on);"
    )
    @ToolReturns(
        description = "切换后的状态",
        jsonExample = "{\"success\": true, \"data\": {\"on\": true}, \"error\": null}"
    )
    fun flashlightToggle(): String = setTorch(!torchOn)

    private fun setTorch(enable: Boolean): String {
        val id = torchCameraId ?: return error("当前设备没有可用的闪光灯")
        return runCatching {
            cameraManager.setTorchMode(id, enable)
            torchOn = enable
            success("on" to enable)
        }.getOrElse { e ->
            // 常见错误：CameraAccessException、相机被其他 App 占用
            error("控制手电筒失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun findTorchCameraId(): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
}
