package com.hfad.mantou.data

import android.net.Uri

/**
 * 图片选择项数据类
 * @param id 图片ID
 * @param uri 图片Uri
 * @param isSelected 是否选中
 */
data class ImageItem(
    val id: Long,
    val uri: Uri,
    var isSelected: Boolean = false
)




