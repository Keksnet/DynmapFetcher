package de.neo.dynmapfetcher.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.*
import de.neo.dynmapfetcher.Configuration
import de.neo.dynmapfetcher.Main
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class WebServer(private val localConfig: Configuration, port: Int) {

    private val server: HttpServer

    init {
        server = HttpServer.create(InetSocketAddress(port), 0)
        if (localConfig.getConfig()["web"].asJsonObject["auth"].asBoolean) {
            server.createContext("/", UIHandler()).authenticator = AuthHandler()
            server.createContext("/api", APIHandler()).authenticator = AuthHandler()
        } else {
            server.createContext("/", UIHandler())
            server.createContext("/api", APIHandler())
        }
        server.start()
    }

    private inner class AuthHandler : Authenticator() {

        override fun authenticate(exch: HttpExchange?): Result {
            if (exch == null) return Retry(401)
            if (exch.requestHeaders["Authorization"] == null) {
                exch.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Login Dynmap Fetcher\"")
                return Retry(401)
            }
            if (exch.requestHeaders["Authorization"]?.size == 0) {
                exch.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Login Dynmap Fetcher\"")
                return Retry(401)
            }
            val authHeader = exch.requestHeaders["Authorization"]?.get(0)
            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                exch.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Login Dynmap Fetcher\"")
                return Failure(401)
            }
            val auth = authHeader.substring(6)
            val decoded = String(Base64.getDecoder().decode(auth))
            val split = decoded.split(":")
            if (split.size != 2) {
                exch.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Login Dynmap Fetcher\"")
                return Failure(403)
            }
            val username = split[0]
            val providedPassword = split[1]
            val password = localConfig.getConfig()["web"].asJsonObject["accounts"].asJsonObject[username]
                .asJsonObject["password"].asString
            if (providedPassword != password) {
                exch.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Login Dynmap Fetcher\"")
                return Failure(403)
            }
            return Success(HttpPrincipal(username, "Login"))
        }
    }

    private inner class UIHandler : HttpHandler {

        private val cache = HashMap<String, String>()

        override fun handle(exchange: HttpExchange?) {
            if (exchange == null) {
                println("Exchange is null")
                return
            }
            try {
                if (exchange.principal.realm != "Login") {
                    exchange.sendResponseHeaders(403, 0)
                    exchange.responseBody.close()
                    return
                }
                when (val path = exchange.requestURI.path) {
                    "/" -> {
                        exchange.responseHeaders.add("Content-Type", "text/html")
                        exchange.sendResponseHeaders(200, 0)
                        val html = if (exchange.requestURI.query != null && exchange.requestURI.query.contains("force")) {
                            Files.readString(Paths.get("web", "index.html"))
                        } else {
                            cache["index.html"] ?: Files.readString(Paths.get("web", "index.html"))
                        }
                        cache["index.html"] = html
                        exchange.responseBody.write(html.toByteArray())
                    }

                    "/favicon.ico" -> {
                        exchange.sendResponseHeaders(404, 0)
                    }

                    else -> {
                        println("Path: $path")
                    }
                }
                exchange.responseBody.close()
            } catch (e: Exception) {
                val printWriter = StringPrintWriter()
                printWriter.write("<html><body><h1>Error loading page</h1><pre>")
                e.printStackTrace(printWriter)
                printWriter.write("</pre></body></html>")
                printWriter.toString()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(500, 0)
                exchange.responseBody.write(printWriter.toString().toByteArray())
                exchange.responseBody.close()
            }
        }
    }

    private inner class APIHandler : HttpHandler {

        override fun handle(exchange: HttpExchange?) {
            if (exchange == null) return
            if (exchange.principal.realm != "Login") {
                exchange.sendResponseHeaders(403, 0)
                return
            }
            val path = exchange.requestURI.path.replace("/api", "")
            val fetcher = Main.getInstance().getFetcher()
            val gson = Gson()
            when (path) {
                "/raw" -> {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, 0)
                    val json = fetcher.getMap()
                    exchange.responseBody.write(json.toString().trim().toByteArray())
                    exchange.responseBody.close()
                }

                "/players" -> {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, 0)
                    val players =
                        gson.fromJson(fetcher.getMap().toString().trim(), JsonObject::class.java)["players"].asJsonArray
                    val json = JsonObject()
                    json.add("players", players)
                    json.addProperty("count", players.size())
                    exchange.responseBody.write(gson.toJson(json).toByteArray())
                    exchange.responseBody.close()
                }

                else -> {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(404, 0)
                    exchange.responseBody.write("{\"error\":\"Not found\", \"path\":\"$path\"}".toByteArray())
                    exchange.responseBody.close()
                }
            }
        }
    }

}

class StringPrintWriter(private val stream: StringStream = StringStream()) : PrintWriter(stream) {

    class StringStream : OutputStream() {
        private val buffer = StringBuffer()

        override fun write(b: Int) {
            buffer.append(b.toChar())
        }

        override fun toString() = buffer.toString().trim()
    }

    override fun println(x: String) {
        super.print(x)
        super.println()
    }

    override fun toString() = stream.toString()
}