package com.hfad.mantou.view.glass

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object LiquidGlass {

    private val explicitStyles = WeakHashMap<View, GlassStyle>()
    private val hosts = WeakHashMap<View, WeakReference<GlassHostView>>()

    @JvmStatic
    fun install(activity: ComponentActivity, root: View): GlassHandle {
        findHost(root)?.let { host ->
            host.refreshTargets()
            return GlassHandle(host, host.contentRoot)
        }
        val parent = root.parent as? ViewGroup
            ?: error("LiquidGlass.install must be called after setContentView")
        val index = parent.indexOfChild(root)
        val layoutParams = root.layoutParams
        parent.removeViewAt(index)
        val host = GlassHostView(activity, root)
        parent.addView(host, index, layoutParams)
        hosts[root] = WeakReference(host)
        host.post { host.refreshTargets() }
        return GlassHandle(host, root)
    }

    @JvmStatic
    fun refresh(root: View) {
        findHost(root)?.refreshTargets()
            ?: root.post { findHost(root)?.refreshTargets() }
    }

    @JvmStatic
    fun attach(view: View, style: GlassStyle) {
        explicitStyles[view] = style
        findHost(view)?.refreshTargets()
            ?: view.post { findHost(view)?.refreshTargets() }
    }

    @JvmStatic
    fun detach(view: View) {
        explicitStyles.remove(view)
        findHost(view)?.refreshTargets()
    }

    @JvmStatic
    fun decorate(dialog: Dialog, decorateSurface: Boolean = true): GlassHandle? {
        val activity = dialog.context.findActivity() ?: return null
        val window = dialog.window ?: return null
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val decorView = window.decorView
        if (decorView.findViewTreeLifecycleOwner() == null) {
            decorView.setViewTreeLifecycleOwner(activity)
        }
        if (decorView.findViewTreeViewModelStoreOwner() == null) {
            decorView.setViewTreeViewModelStoreOwner(activity)
        }
        if (decorView.findViewTreeSavedStateRegistryOwner() == null) {
            decorView.setViewTreeSavedStateRegistryOwner(activity)
        }
        val root = decorView.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val content = if (decorateSurface && root.childCount == 1) root.getChildAt(0) else root
        if (decorateSurface && GlassStyle.fromTag(content.tag) == null && explicitStyles[content] == null) {
            explicitStyles[content] = GlassStyle.Sheet
        }
        return install(activity, content)
    }

    internal fun styleFor(view: View): GlassStyle? = explicitStyles[view]

    internal fun onHostDisposed(host: GlassHostView) {
        val iterator = hosts.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.get() === host) iterator.remove()
        }
    }

    private fun findHost(view: View): GlassHostView? {
        hosts[view]?.get()?.takeUnless { it.isDisposed }?.let { return it }
        var current: View? = view
        while (current != null) {
            if (current is GlassHostView) return current
            current = current.parent as? View
        }
        val iterator = hosts.entries.iterator()
        while (iterator.hasNext()) {
            val host = iterator.next().value.get()
            if (host == null || host.isDisposed) {
                iterator.remove()
            } else if (isDescendant(host.contentRoot, view)) {
                return host
            }
        }
        return null
    }

    private fun isDescendant(root: View, candidate: View): Boolean {
        if (root === candidate) return true
        if (root !is ViewGroup) return false
        for (index in 0 until root.childCount) {
            if (isDescendant(root.getChildAt(index), candidate)) return true
        }
        return false
    }

    private fun Context.findActivity(): ComponentActivity? {
        var current: Context? = this
        val visited = HashSet<Context>()
        while (current != null && visited.add(current)) {
            if (current is ComponentActivity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
