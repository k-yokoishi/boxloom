package dev.boxloom.server.core.http

import com.sun.net.httpserver.HttpExchange
import dev.boxloom.server.core.ApiException
import java.util.regex.Pattern

internal fun interface ExchangeHandler {
    fun handle(exchange: HttpExchange, parameters: RouteParameters)
}

internal class RouteParameters(
    private val values: Map<String, String>,
) {
    operator fun get(name: String): String =
        requireNotNull(values[name]) { "Route parameter '$name' is not defined" }
}

internal class Router(
    private val prefix: String = "",
) {
    private val routes = mutableListOf<Route>()

    fun get(path: String, handler: ExchangeHandler) {
        add("GET", path, handler)
    }

    fun post(path: String, handler: ExchangeHandler) {
        add("POST", path, handler)
    }

    fun dispatch(exchange: HttpExchange) {
        val pathMatches = routes.mapNotNull { route ->
            route.match(exchange.requestURI.rawPath)
        }

        if (pathMatches.isEmpty()) {
            throw ApiException(
                404,
                "ROUTE_NOT_FOUND",
                "No API route matches this request",
            )
        }

        val match = pathMatches.firstOrNull { it.route.method == exchange.requestMethod }
        if (match == null) {
            val allowed = pathMatches.map { it.route.method }.distinct()
            exchange.responseHeaders.set("Allow", allowed.joinToString(", "))
            val requirement = if (allowed.size == 1) {
                allowed.single()
            } else {
                "one of ${allowed.joinToString(", ")}"
            }
            throw ApiException(
                405,
                "METHOD_NOT_ALLOWED",
                "This route requires $requirement",
            )
        }

        match.route.handler.handle(exchange, match.parameters)
    }

    private fun add(method: String, path: String, handler: ExchangeHandler) {
        routes += Route(method, PathTemplate.compile(joinPaths(prefix, path)), handler)
    }

    private data class Route(
        val method: String,
        val template: PathTemplate,
        val handler: ExchangeHandler,
    ) {
        fun match(path: String): RouteMatch? {
            val parameters = template.match(path) ?: return null
            return RouteMatch(this, parameters)
        }
    }

    private data class RouteMatch(
        val route: Route,
        val parameters: RouteParameters,
    )

    private class PathTemplate(
        private val pattern: Pattern,
        private val parameterNames: List<String>,
    ) {
        fun match(path: String): RouteParameters? {
            val matcher = pattern.matcher(path)
            if (!matcher.matches()) return null

            val values = parameterNames.mapIndexed { index, name ->
                name to matcher.group(index + 1)
            }.toMap()
            return RouteParameters(values)
        }

        companion object {
            private val PARAMETER = Regex("^\\{([A-Za-z][A-Za-z0-9_]*)}$")

            fun compile(template: String): PathTemplate {
                require(template.startsWith('/')) { "Route path must start with '/'" }

                if (template == "/") {
                    return PathTemplate(Pattern.compile("^/$"), emptyList())
                }

                val parameterNames = mutableListOf<String>()
                val pattern = buildString {
                    append('^')
                    template.removePrefix("/").split('/').forEach { segment ->
                        require(segment.isNotEmpty()) { "Route path must not contain empty segments" }
                        append('/')

                        val parameter = PARAMETER.matchEntire(segment)?.groupValues?.get(1)
                        if (parameter == null) {
                            append(Pattern.quote(segment))
                        } else {
                            require(parameter !in parameterNames) {
                                "Route parameter '$parameter' is duplicated"
                            }
                            parameterNames += parameter
                            append("([^/]+)")
                        }
                    }
                    append('$')
                }

                return PathTemplate(Pattern.compile(pattern), parameterNames)
            }
        }
    }

    companion object {
        private fun joinPaths(prefix: String, path: String): String {
            require(prefix.isEmpty() || prefix.startsWith('/')) {
                "Route prefix must start with '/'"
            }
            require(path.startsWith('/')) { "Route path must start with '/'" }
            return prefix.trimEnd('/') + path
        }
    }
}
