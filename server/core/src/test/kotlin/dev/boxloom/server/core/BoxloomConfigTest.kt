package dev.boxloom.server.core

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoxloomConfigTest {
    @Test
    fun `creates and loads the default loopback config`() = withConfigPath { configPath ->
        val config = BoxloomConfig.load(configPath, emptyMap())

        assertTrue(Files.exists(configPath))
        assertTrue(config.bindAddress.isLoopbackAddress)
        assertEquals(28_886, config.port)
        assertEquals(5_000, config.requestTimeout.toMillis())
        assertFalse(config.authenticationEnabled)
    }

    @Test
    fun `loads authenticated settings from the config file`() = withConfigPath { configPath ->
        Files.writeString(
            configPath,
            """
                {
                  "bindHost": "0.0.0.0",
                  "port": 30000,
                  "authToken": "file-secret",
                  "requestTimeoutMs": 7000
                }
            """.trimIndent(),
        )

        val config = BoxloomConfig.load(configPath, emptyMap())

        assertEquals("0.0.0.0", config.displayHost)
        assertEquals(30_000, config.port)
        assertEquals("file-secret", config.authToken)
        assertEquals(7_000, config.requestTimeout.toMillis())
        assertTrue(config.authenticationEnabled)
    }

    @Test
    fun `environment variables override config file values`() = withConfigPath { configPath ->
        Files.writeString(
            configPath,
            """
                {
                  "bindHost": "127.0.0.1",
                  "port": 28886,
                  "authToken": "file-secret",
                  "requestTimeoutMs": 5000
                }
            """.trimIndent(),
        )

        val config = BoxloomConfig.load(
            configPath,
            mapOf(
                "BOXLOOM_BIND_HOST" to "0.0.0.0",
                "BOXLOOM_PORT" to "30001",
                "BOXLOOM_AUTH_TOKEN" to "environment-secret",
                "BOXLOOM_REQUEST_TIMEOUT_MS" to "8000",
            ),
        )

        assertEquals("0.0.0.0", config.displayHost)
        assertEquals(30_001, config.port)
        assertEquals("environment-secret", config.authToken)
        assertEquals(8_000, config.requestTimeout.toMillis())
    }

    @Test
    fun `rejects an unauthenticated non-loopback listener`() = withConfigPath { configPath ->
        Files.writeString(
            configPath,
            """
                {
                  "bindHost": "0.0.0.0",
                  "port": 28886,
                  "authToken": "",
                  "requestTimeoutMs": 5000
                }
            """.trimIndent(),
        )

        val exception = assertFailsWith<IllegalStateException> {
            BoxloomConfig.load(configPath, emptyMap())
        }

        assertTrue(exception.message.orEmpty().contains("authToken is required"))
    }

    private fun withConfigPath(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("boxloom-config-test")

        try {
            block(directory.resolve("boxloom.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
