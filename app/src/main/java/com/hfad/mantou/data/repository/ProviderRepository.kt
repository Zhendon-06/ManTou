package com.hfad.mantou.data.repository

import com.hfad.mantou.data.database.ProviderDao
import com.hfad.mantou.data.database.ProviderEntity
import com.hfad.mantou.data.database.ProviderModelEntity
import kotlinx.coroutines.flow.Flow

class ProviderRepository(private val dao: ProviderDao) {

    // ============ Provider ============

    suspend fun createProvider(name: String): Long =
        dao.insertProvider(ProviderEntity(name = name))

    suspend fun updateProvider(provider: ProviderEntity) = dao.updateProvider(provider)

    suspend fun deleteProvider(providerId: Long) = dao.deleteProvider(providerId)

    fun getAllProviders(): Flow<List<ProviderEntity>> = dao.getAllProviders()

    suspend fun getAllProvidersOnce(): List<ProviderEntity> = dao.getAllProvidersOnce()

    suspend fun getProviderById(providerId: Long): ProviderEntity? =
        dao.getProviderById(providerId)

    suspend fun getFirstProvider(): ProviderEntity? = dao.getFirstProvider()

    // ============ Models ============

    /** 用新拉取的模型清单替换 Provider 现有模型列表。 */
    suspend fun replaceModels(providerId: Long, modelNames: List<String>) {
        dao.deleteModelsForProvider(providerId)
        if (modelNames.isNotEmpty()) {
            val now = System.currentTimeMillis()
            dao.insertModels(modelNames.mapIndexed { idx, name ->
                ProviderModelEntity(
                    providerId = providerId,
                    modelName = name,
                    createTime = now + idx
                )
            })
        }
    }

    fun getModelsForProvider(providerId: Long): Flow<List<ProviderModelEntity>> =
        dao.getModelsForProvider(providerId)

    suspend fun getModelsForProviderOnce(providerId: Long): List<ProviderModelEntity> =
        dao.getModelsForProviderOnce(providerId)
}
