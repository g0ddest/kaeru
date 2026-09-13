package app.kaeru.di

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.data.pairing.LanPairingAddresses
import app.kaeru.data.pairing.NetworkLanAddresses
import app.kaeru.data.pairing.PairingAddresses
import app.kaeru.data.pairing.PairingTimeouts
import app.kaeru.data.pairing.SocketPairingClient
import app.kaeru.data.pairing.SocketPairingServer
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.TvPairingServer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PairingModule {
    /** The shipped clocks. Their reasons are written down on the type itself. */
    @Provides
    @Singleton
    fun pairingTimeouts(): PairingTimeouts = PairingTimeouts()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PairingBindings {
    // Unscoped on purpose: the implementations are already @Singleton, so these hand out the one
    // instance each. The server in particular has to be one instance — it owns a port.
    @Binds
    abstract fun lanAddresses(impl: NetworkLanAddresses): LanAddresses

    @Binds
    abstract fun pairingAddresses(impl: LanPairingAddresses): PairingAddresses

    @Binds
    abstract fun tvPairingServer(impl: SocketPairingServer): TvPairingServer

    @Binds
    abstract fun pairingClient(impl: SocketPairingClient): PairingClient
}
