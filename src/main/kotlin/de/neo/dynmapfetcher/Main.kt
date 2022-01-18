package de.neo.dynmapfetcher

import de.neo.dynmapfetcher.web.WebServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class Main(private val url: String, private val localConfig: Configuration) {

    private val fetcher = Fetcher("$url/%s", localConfig.getConfig()["fetcher"].asJsonObject)
    private val config: Configuration

    companion object {
        private var instance: Main? = null

        fun getInstance(): Main {
            return instance!!
        }
    }

    fun getFetcher(): Fetcher {
        return fetcher
    }

    fun getConfig(): Configuration {
        return config
    }

    init {
        instance = this
        val mapDataPath = Path.of("mapData")
        if(!Files.exists(mapDataPath)) {
            Files.createDirectory(mapDataPath)
        }
        val tilePath = Path.of("tiles")
        if(!Files.exists(tilePath)) {
            Files.createDirectory(tilePath)
        }
        config = Configuration()
        config.fetch("$url/%s")
        Thread(fetcher).start()
        println("Started fetching...")
        val telnetPort = localConfig.getConfig()["telnet"].asJsonObject["port"].asInt
        Thread {
            DynmapTelnet(telnetPort).start()
        }.start()
        println("Started telnet on port $telnetPort...")
        val webPort = localConfig.getConfig()["web"].asJsonObject["port"].asInt
        Thread {
            WebServer(localConfig, webPort)
        }.start()
        println("Started web server on port $webPort...")
    }

}

fun main(args: Array<String>) {
    if(args.size > 1) {
        println("Usage: java -jar DynmapFetcher.jar <url>")
        return
    }
    val localConfig = Configuration()
    if(!Files.exists(Paths.get("config.yml"))) {
        Files.writeString(Paths.get("config.yml"), Main::class.java.getResourceAsStream("/config.yml").bufferedReader().readText())
    }
    localConfig.load(Paths.get("config.json"))
    val url = if(args.size == 1) args[0] else localConfig.getConfig()["fetcher"].asJsonObject["url"].asString
    Main(url, localConfig)
}