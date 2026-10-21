package io.kud.tutti.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.net.AndroidTcpConnector
import io.kud.tutti.platform.net.AndroidTlsConnector
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.grpc.SonyCloudPlane
import io.kud.tutti.protocol.grpc.UnavailableSonyCloudPlane
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnector
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectorModule {
    @Binds
    abstract fun tcpConnector(impl: AndroidTcpConnector): TcpConnector

    @Binds
    abstract fun tlsConnector(impl: AndroidTlsConnector): TlsConnector
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    /** files/datastore/tutti.preferences_pb: backed up, no secrets. */
    @Provides
    @Singleton
    @Named(TuttiStore.TUTTI)
    fun tuttiDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(TuttiStore.TUTTI) })

    /** files/datastore/secure.preferences_pb: excluded from backup, values Keystore-encrypted. */
    @Provides
    @Singleton
    @Named(SecureStore.SECURE)
    fun secureDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(SecureStore.SECURE) })

    /** The Sony-cloud envelope is undocumented: bound to "non disponible" (docs/architecture.md). */
    @Provides
    @Singleton
    fun sonyCloudPlane(): SonyCloudPlane = UnavailableSonyCloudPlane()
}
