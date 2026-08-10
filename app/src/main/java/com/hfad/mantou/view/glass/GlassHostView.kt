package com.hfad.mantou.view.glass

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlin.math.roundToInt

internal class GlassHostView(
    context: Context,
    internal val contentRoot: View
) : FrameLayout(context) {

    private data class Target(
        val view: View,
        val style: GlassStyle,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val clipLeft: Int,
        val clipTop: Int,
        val clipRight: Int,
        val clipBottom: Int,
        val alpha: Float
    )

    private val composeView = ComposeView(context)
    private val originalBackgrounds = LinkedHashMap<View, android.graphics.drawable.Drawable?>()
    private val backgroundPlaceholders = LinkedHashMap<View, ColorDrawable>()
    private val originalAlphas = LinkedHashMap<View, Float>()
    private var targets by mutableStateOf(emptyList<Target>())
    private var disposed = false

    internal val isDisposed: Boolean
        get() = disposed

    private val onGlobalLayout = ViewTreeObserver.OnGlobalLayoutListener {
        refreshTargets()
    }
    private val onScrollChanged = ViewTreeObserver.OnScrollChangedListener {
        updateTargetBounds()
    }
    private val onContentAttachStateChange = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.post { refreshTargets() }
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        composeView.isClickable = false
        composeView.isFocusable = false
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        composeView.setContent { GlassContent() }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayout)
        viewTreeObserver.addOnScrollChangedListener(onScrollChanged)
        contentRoot.addOnAttachStateChangeListener(onContentAttachStateChange)
    }

    fun refreshTargets() {
        if (disposed) return
        val styledViews = ArrayList<Pair<View, GlassStyle>>()
        collectStyledViews(contentRoot, styledViews)
        val styledSet = styledViews.mapTo(HashSet()) { it.first }
        originalBackgrounds.keys.toList().forEach { oldView ->
            if (oldView !in styledSet) {
                oldView.background = originalBackgrounds.remove(oldView)
                backgroundPlaceholders.remove(oldView)
            }
        }
        originalAlphas.keys.toList().forEach { oldView ->
            if (oldView !in styledSet) {
                oldView.alpha = originalAlphas.remove(oldView) ?: 1f
            }
        }
        styledViews.forEach { (view, _) ->
            val placeholder = backgroundPlaceholders.getOrPut(view) {
                ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
            val currentBackground = view.background
            if (currentBackground !== placeholder) {
                originalBackgrounds[view] = currentBackground
            } else if (!originalBackgrounds.containsKey(view)) {
                originalBackgrounds[view] = null
            }
            if (currentBackground !== placeholder) {
                view.background = placeholder
            }
            if (view.alpha != HIDDEN_ALPHA || view !in originalAlphas) {
                originalAlphas[view] = view.alpha
            }
            if (view.alpha != HIDDEN_ALPHA) {
                view.alpha = HIDDEN_ALPHA
            }
        }
        updateTargetBounds(styledViews)
    }

    fun dispose() {
        if (disposed) return
        release()
        (parent as? ViewGroup)?.let { parent ->
            val index = parent.indexOfChild(this)
            val layoutParams = layoutParams
            removeView(composeView)
            (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
            parent.removeViewAt(index)
            parent.addView(contentRoot, index, layoutParams)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!disposed) release()
    }

    private fun release() {
        disposed = true
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayout)
            viewTreeObserver.removeOnScrollChangedListener(onScrollChanged)
        }
        originalBackgrounds.forEach { (view, background) -> view.background = background }
        originalAlphas.forEach { (view, alpha) -> view.alpha = alpha }
        originalBackgrounds.clear()
        backgroundPlaceholders.clear()
        originalAlphas.clear()
        contentRoot.removeOnAttachStateChangeListener(onContentAttachStateChange)
        LiquidGlass.onHostDisposed(this)
        composeView.disposeComposition()
    }

    private fun collectStyledViews(view: View, result: MutableList<Pair<View, GlassStyle>>) {
        val style = LiquidGlass.styleFor(view) ?: GlassStyle.fromTag(view.tag)
        val effectiveAlpha = if (view in originalAlphas && view.alpha == HIDDEN_ALPHA) {
            originalAlphas.getValue(view)
        } else {
            view.alpha
        }
        if (style != null && view.isShown && effectiveAlpha > 0f) {
            result += view to style
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectStyledViews(view.getChildAt(index), result)
            }
        }
    }

    private fun updateTargetBounds(styledViews: List<Pair<View, GlassStyle>>? = null) {
        if (disposed || width == 0 || height == 0) return
        val styled = styledViews ?: buildList { collectStyledViews(contentRoot, this) }
        val hostLocation = IntArray(2)
        getLocationOnScreen(hostLocation)
        val hostBounds = Rect(
            hostLocation[0],
            hostLocation[1],
            hostLocation[0] + width,
            hostLocation[1] + height
        )
        val nextTargets = styled.mapNotNull { (view, style) ->
            if (view.width <= 0 || view.height <= 0) return@mapNotNull null
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val visibleBounds = Rect()
            val hasGlobalBounds = view.getGlobalVisibleRect(visibleBounds) && visibleBounds.intersect(hostBounds)
            if (!hasGlobalBounds) {
                visibleBounds.set(
                    location[0].coerceAtLeast(hostBounds.left),
                    location[1].coerceAtLeast(hostBounds.top),
                    (location[0] + view.width).coerceAtMost(hostBounds.right),
                    (location[1] + view.height).coerceAtMost(hostBounds.bottom)
                )
            }
            if (visibleBounds.isEmpty) {
                return@mapNotNull null
            }
            Target(
                view = view,
                style = style,
                left = location[0] - hostLocation[0],
                top = location[1] - hostLocation[1],
                width = view.width,
                height = view.height,
                clipLeft = visibleBounds.left - location[0],
                clipTop = visibleBounds.top - location[1],
                clipRight = visibleBounds.right - location[0],
                clipBottom = visibleBounds.bottom - location[1],
                alpha = originalAlphas[view] ?: view.alpha
            )
        }
        if (nextTargets != targets) targets = nextTargets
    }

    @Composable
    private fun GlassContent() {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    (contentRoot.parent as? ViewGroup)?.removeView(contentRoot)
                    contentRoot
                },
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                update = { refreshTargets() }
            )
            targets.forEach { target ->
                GlassSurface(target, backdrop)
            }
        }
        DisposableEffect(Unit) {
            refreshTargets()
            onDispose { }
        }
    }

    @Composable
    private fun GlassSurface(
        target: Target,
        backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
    ) {
        val density = resources.displayMetrics.density
        val style = target.style
        val cornerRadius = if (style.cornerRadiusDp >= 999f) {
            (target.height / density / 2f).dp
        } else {
            style.cornerRadiusDp.dp
        }
        Canvas(
            Modifier
                .offset { IntOffset(target.left, target.top) }
                .size((target.width / density).dp, (target.height / density).dp)
                .drawWithContent {
                    clipRect(
                        left = target.clipLeft.toFloat(),
                        top = target.clipTop.toFloat(),
                        right = target.clipRight.toFloat(),
                        bottom = target.clipBottom.toFloat()
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(cornerRadius) },
                    effects = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            vibrancy()
                            blur(style.blurRadiusDp.dp.toPx())
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(
                                refractionHeight = style.refractionHeightDp.dp.toPx(),
                                refractionAmount = style.refractionAmountDp.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        Highlight(
                            width = 1f.dp,
                            blurRadius = 0.35f.dp,
                            alpha = 0.76f,
                            style = com.kyant.backdrop.highlight.HighlightStyle.Default(
                                angle = 45f,
                                falloff = 1.2f
                            )
                        )
                    },
                    shadow = null,
                    onDrawSurface = {
                        drawRect(style.surfaceColor)
                        if (style == GlassStyle.Accent) {
                            drawRect(Color(0xFF1687FF).copy(alpha = 0.16f), blendMode = BlendMode.Color)
                        }
                    }
                )
        ) {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val alpha = (target.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
                val saveCount = if (alpha < 255) {
                    nativeCanvas.saveLayerAlpha(
                        0f,
                        0f,
                        target.width.toFloat(),
                        target.height.toFloat(),
                        alpha
                    )
                } else {
                    nativeCanvas.save()
                }
                val hiddenAlpha = target.view.alpha
                target.view.alpha = target.alpha
                try {
                    target.view.draw(nativeCanvas)
                } finally {
                    target.view.alpha = hiddenAlpha
                }
                nativeCanvas.restoreToCount(saveCount)
            }
        }
    }

    private companion object {
        const val HIDDEN_ALPHA = 0.001f
    }
}
