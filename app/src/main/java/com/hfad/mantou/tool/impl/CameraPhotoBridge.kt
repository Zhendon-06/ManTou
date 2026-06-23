package com.hfad.mantou.tool.impl

import java.lang.ref.WeakReference

object CameraPhotoBridge {
    interface Host {
        fun requestCameraPhoto(callbackName: String?)
    }

    private val hostRefs = mutableListOf<WeakReference<Host>>()

    @Synchronized
    fun attach(host: Host) {
        hostRefs.removeAll { it.get() == null || it.get() === host }
        hostRefs.add(WeakReference(host))
    }

    @Synchronized
    fun detach(host: Host) {
        hostRefs.removeAll { it.get() == null || it.get() === host }
    }

    @Synchronized
    fun requestPhoto(callbackName: String?): Boolean {
        hostRefs.removeAll { it.get() == null }
        val host = hostRefs.lastOrNull()?.get() ?: return false
        host.requestCameraPhoto(callbackName)
        return true
    }
}
