package com.tera.down.core.di

import android.content.Context
import com.tera.down.core.database.AppDatabase
import com.tera.down.feature.downloader.DownloadEngine
import com.tera.down.feature.downloader.DownloadQueueCoordinator
import com.tera.down.feature.downloader.DownloadQueueCoordinatorImpl
import com.tera.down.feature.downloader.OkHttpDownloadEngine
import com.tera.down.feature.downloader.StorageManager
import com.tera.down.feature.queue.DownloadQueueRepository
import com.tera.down.feature.queue.DownloadQueueRepositoryImpl
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import com.tera.down.feature.resolver.ShareLinkResolverRegistry
import com.tera.down.feature.resolver.ShareLinkResolverRegistryImpl
import com.tera.down.feature.resolver.TeraboxShareLinkResolver
import androidx.datastore.preferences.preferencesDataStore
import com.tera.down.core.network.NetworkConnectivityMonitor
import com.tera.down.core.network.SystemNetworkConnectivityMonitor
import com.tera.down.feature.settings.DataStoreSettingsRepository
import com.tera.down.feature.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "teradown_settings")

/**
 * Lightweight application-level dependency container avoiding heavy reflection frameworks.
 */
interface AppContainer {
    val database: AppDatabase
    val downloadQueueRepository: DownloadQueueRepository
    val libraryRepository: LibraryRepository
    val storageManager: StorageManager
    val downloadEngine: DownloadEngine
    val downloadQueueCoordinator: DownloadQueueCoordinator
    val teraboxShareLinkResolver: TeraboxShareLinkResolver
    val shareLinkResolverRegistry: ShareLinkResolverRegistry
    val playbackPositionTracker: com.tera.down.feature.player.PlaybackPositionTracker
    val manifestRepository: com.tera.down.feature.manifest.ManifestRepository
    val downloadNotificationManager: com.tera.down.feature.downloader.DownloadNotificationManager
    val downloadRecoveryManager: com.tera.down.feature.downloader.DownloadRecoveryManager
    val settingsRepository: com.tera.down.feature.settings.SettingsRepository
    val networkConnectivityMonitor: com.tera.down.core.network.NetworkConnectivityMonitor
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    override val downloadNotificationManager: com.tera.down.feature.downloader.DownloadNotificationManager by lazy {
        com.tera.down.feature.downloader.DownloadNotificationManagerImpl(context)
    }

    override val manifestRepository: com.tera.down.feature.manifest.ManifestRepository by lazy {
        com.tera.down.feature.manifest.ManifestRepositoryImpl(
            downloadManifestDao = database.downloadManifestDao()
        ).also { repo ->
            // Reconcile interrupted tasks on startup
            applicationScope.launch(Dispatchers.IO) {
                repo.reconcileInterruptedTasks()
            }
        }
    }

    override val downloadQueueRepository: DownloadQueueRepository by lazy {
        DownloadQueueRepositoryImpl(
            downloadTaskDao = database.downloadTaskDao(),
            libraryMediaDao = database.libraryMediaDao(),
            manifestReporter = manifestRepository
        )
    }

    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepositoryImpl(
            libraryMediaDao = database.libraryMediaDao()
        )
    }

    override val storageManager: StorageManager by lazy {
        StorageManager(context)
    }

    override val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(context.dataStore)
    }

    override val networkConnectivityMonitor: NetworkConnectivityMonitor by lazy {
        SystemNetworkConnectivityMonitor(context)
    }

    override val downloadEngine: DownloadEngine by lazy {
        OkHttpDownloadEngine(storageManager = storageManager)
    }

    override val downloadQueueCoordinator: DownloadQueueCoordinator by lazy {
        DownloadQueueCoordinatorImpl(
            queueRepository = downloadQueueRepository,
            downloadEngine = downloadEngine,
            storageManager = storageManager,
            scope = applicationScope,
            manifestReporter = manifestRepository,
            context = context,
            settingsRepository = settingsRepository,
            networkConnectivityMonitor = networkConnectivityMonitor
        )
    }

    override val downloadRecoveryManager: com.tera.down.feature.downloader.DownloadRecoveryManager by lazy {
        com.tera.down.feature.downloader.DownloadRecoveryManagerImpl(
            downloadTaskDao = database.downloadTaskDao(),
            queueRepository = downloadQueueRepository,
            storageManager = storageManager,
            coordinator = downloadQueueCoordinator,
            manifestReporter = manifestRepository
        ).also { recoveryManager ->
            applicationScope.launch(Dispatchers.IO) {
                recoveryManager.reconcileAndRecover()
            }
        }
    }

    override val teraboxShareLinkResolver: TeraboxShareLinkResolver by lazy {
        TeraboxShareLinkResolver()
    }

    override val shareLinkResolverRegistry: ShareLinkResolverRegistry by lazy {
        ShareLinkResolverRegistryImpl(listOf(teraboxShareLinkResolver))
    }

    override val playbackPositionTracker: com.tera.down.feature.player.PlaybackPositionTracker by lazy {
        com.tera.down.feature.player.PlaybackPositionTracker(
            libraryRepository = libraryRepository,
            scope = applicationScope
        )
    }
}
