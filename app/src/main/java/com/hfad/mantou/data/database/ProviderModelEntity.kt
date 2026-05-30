package com.hfad.mantou.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Provider 下挂的模型
 *
 * 删除 Provider 时级联删除其模型。
 */
@Entity(
    tableName = "provider_models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["providerId"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["providerId"])]
)
data class ProviderModelEntity(
    @PrimaryKey(autoGenerate = true)
    val modelId: Long = 0,

    val providerId: Long,

    val modelName: String,

    val createTime: Long = System.currentTimeMillis()
)
