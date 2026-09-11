package dev.boxloom.server.core.http

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.BoxloomConfig
import dev.boxloom.server.core.BoxloomEventBroker
import dev.boxloom.server.core.MinecraftOperations
import dev.boxloom.server.core.api.chat.ChatRoutes
import dev.boxloom.server.core.api.events.EventRoutes
import dev.boxloom.server.core.api.players.PlayerRoutes
import dev.boxloom.server.core.api.world.WorldRoutes

internal class BoxloomHttpApplication(
    config: BoxloomConfig,
    minecraft: MinecraftOperations,
    events: BoxloomEventBroker,
) {
    private val authenticator = BearerAuthenticator(config.authToken)
    private val operations = MinecraftOperationRunner(config.requestTimeout)
    private val router = Router(API_PREFIX).apply {
        ChatRoutes(minecraft, operations).register(this)
        PlayerRoutes(minecraft, operations).register(this)
        WorldRoutes(minecraft, operations).register(this)
        EventRoutes(events).register(this)
    }

    fun handle(exchange: HttpExchange) {
        try {
            authenticator.requireAuthorization(exchange)
            router.dispatch(exchange)
        } catch (exception: ApiException) {
            HttpExchangeSupport.sendError(
                exchange,
                exception.status,
                exception.code,
                exception.message.orEmpty(),
            )
        } catch (throwable: Throwable) {
            LOGGER.log(System.Logger.Level.ERROR, "Unhandled boxloom HTTP error", throwable)
            HttpExchangeSupport.sendError(
                exchange,
                500,
                "INTERNAL_ERROR",
                "boxloom could not complete the request",
            )
        } finally {
            exchange.close()
        }
    }

    companion object {
        private const val API_PREFIX = "/v1"
        private val LOGGER = System.getLogger("boxloom-http")
    }
}
