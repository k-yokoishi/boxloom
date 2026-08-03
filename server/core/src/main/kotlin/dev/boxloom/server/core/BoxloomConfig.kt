package dev.boxloom.server.core

import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration

data class BoxloomConfig(
    val bindAddress: InetAddress,
    val port: Int,
    val authToken: String,
    val requestTimeout: Duration,
) {
    val displayHost: String
        get() = bindAddress.hostAddress

    companion object {
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 28_886
        private const val DEFAULT_TIMEOUT_MS = 5_000L

        fun fromEnvironment(environment: Map<String, String>): BoxloomConfig {
            val host = environment["BOXLOOM_BIND_HOST"].valueOrDefault(DEFAULT_BIND_HOST)
            val port = parseLong(
                "BOXLOOM_PORT",
                environment["BOXLOOM_PORT"].valueOrDefault(DEFAULT_PORT.toString()),
                1,
                65_535,
            ).toInt()
            val authToken = environment["BOXLOOM_AUTH_TOKEN"]

            check(!authToken.isNullOrBlank()) {
                "BOXLOOM_AUTH_TOKEN must be set and non-blank"
            }
            check(authToken.length <= 4_096) {
                "BOXLOOM_AUTH_TOKEN is too long"
            }

            val timeoutMs = parseLong(
                "BOXLOOM_REQUEST_TIMEOUT_MS",
                environment["BOXLOOM_REQUEST_TIMEOUT_MS"].valueOrDefault(DEFAULT_TIMEOUT_MS.toString()),
                100,
                60_000,
            )

            try {
                return BoxloomConfig(
                    InetAddress.getByName(host),
                    port,
                    authToken,
                    Duration.ofMillis(timeoutMs),
                )
            } catch (exception: UnknownHostException) {
                throw IllegalStateException(
                    "BOXLOOM_BIND_HOST cannot be resolved: $host",
                    exception,
                )
            }
        }

        private fun String?.valueOrDefault(fallback: String): String =
            if (isNullOrBlank()) fallback else trim()

        private fun parseLong(
            name: String,
            value: String,
            minimum: Long,
            maximum: Long,
        ): Long {
            val parsed = value.toLongOrNull()
                ?: throw IllegalStateException("$name must be an integer")

            check(parsed in minimum..maximum) {
                "$name must be between $minimum and $maximum"
            }

            return parsed
        }
    }
}
