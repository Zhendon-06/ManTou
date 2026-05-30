package com.hfad.mantou.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {

    // ============ Provider ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ProviderEntity): Long

    @Update
    suspend fun updateProvider(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE providerId = :providerId")
    suspend fun deleteProvider(providerId: Long)

    @Query("SELECT * FROM providers ORDER BY createTime ASC")
    fun getAllProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createTime ASC")
    suspend fun getAllProvidersOnce(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE providerId = :providerId")
    suspend fun getProviderById(providerId: Long): ProviderEntity?

    @Query("SELECT * FROM providers ORDER BY createTime ASC LIMIT 1")
    suspend fun getFirstProvider(): ProviderEntity?

    // ============ Models ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ProviderModelEntity>)

    @Query("DELETE FROM provider_models WHERE providerId = :providerId")
    suspend fun deleteModelsForProvider(providerId: Long)

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY createTime ASC")
    fun getModelsForProvider(providerId: Long): Flow<List<ProviderModelEntity>>

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY createTime ASC")
    suspend fun getModelsForProviderOnce(providerId: Long): List<ProviderModelEntity>
}
