package io.github.nesfeder.supabase.offline.internal.util

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/** On the JVM: the connection was refused or timed out, no route led to the host, or its name did not resolve. */
internal actual fun isConnectFailureOnThisPlatform(failure: Throwable): Boolean =
    failure is ConnectException ||
        failure is UnknownHostException ||
        failure is NoRouteToHostException ||
        failure is UnresolvedAddressException
