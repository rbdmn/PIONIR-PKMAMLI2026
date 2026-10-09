package com.smartplug.app.di

import com.smartplug.app.data.repository.DeviceRepositoryImpl
import com.smartplug.app.data.repository.DeviceControlRepositoryImpl
import com.smartplug.app.data.repository.DiscoveryRepositoryImpl
import com.smartplug.app.data.repository.HistoryRepositoryImpl
import com.smartplug.app.data.repository.PairingRepositoryImpl
import com.smartplug.app.data.repository.RelayRepositoryImpl
import com.smartplug.app.data.repository.StorageRepositoryImpl
import com.smartplug.app.domain.repository.StorageRepository
import com.smartplug.app.data.repository.WifiOnboardingRepositoryImpl
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DiscoveryRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.domain.repository.PairingRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.domain.repository.WifiOnboardingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository

    @Binds
    @Singleton
    abstract fun bindWifiOnboardingRepository(impl: WifiOnboardingRepositoryImpl): WifiOnboardingRepository

    @Binds
    @Singleton
    abstract fun bindDiscoveryRepository(impl: DiscoveryRepositoryImpl): DiscoveryRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindDeviceControlRepository(impl: DeviceControlRepositoryImpl): DeviceControlRepository

    @Binds
    @Singleton
    abstract fun bindRelayRepository(impl: RelayRepositoryImpl): RelayRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindStorageRepository(impl: StorageRepositoryImpl): StorageRepository
}
