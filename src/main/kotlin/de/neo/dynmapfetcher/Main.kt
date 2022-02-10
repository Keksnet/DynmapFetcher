package de.neo.dynmapfetcher

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import de.neo.dynmapfetcher.web.WebServer
import org.bson.Document
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

class Main(private val url: String, private val localConfig: Configuration) {

    private var fetcher: Fetcher? = null
    private val config: Configuration = Configuration()
    private var con: Connection? = null
    private var mongoCollection: MongoCollection<Document>? = null

    companion object {
        private var instance: Main? = null

        fun getInstance(): Main {
            return instance!!
        }
    }

    fun getFetcher(): Fetcher {
        return fetcher!!
    }

    fun getConfig(): Configuration {
        return config
    }

    init {
        instance = this
        config.fetch("$url/%s")
    }

    fun init() {
        fetcher = Fetcher("$url/%s", localConfig.getConfig()["fetcher"].asJsonObject, localConfig.getConfig())

        val mapDataPath = Path.of("mapData")
        if(!Files.exists(mapDataPath)) {
            Files.createDirectory(mapDataPath)
        }
        val tilePath = Path.of("tiles")
        if(!Files.exists(tilePath)) {
            Files.createDirectory(tilePath)
        }

        if(localConfig.getConfig()["sql"].asJsonObject["enabled"].asBoolean) {
            println("Setting up database...")
            con = DriverManager.getConnection("jdbc:sqlite:${localConfig.getConfig()["sql"].asJsonObject["file"].asString}")
            con!!.createStatement().execute("CREATE TABLE IF NOT EXISTS `mapData` (`json` TEXT)")
        }

        if(localConfig.getConfig()["mongodb"].asJsonObject["enabled"].asBoolean) {
            println("Setting up mongodb...")
            val client = MongoClients.create(localConfig.getConfig()["mongodb"].asJsonObject["uri"].asString)
            mongoCollection = client.getDatabase(localConfig.getConfig()["mongodb"].asJsonObject["database"].asString)
                .getCollection(localConfig.getConfig()["mongodb"].asJsonObject["collection"].asString)
        }

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

    fun getDatabaseConnection(): Connection {
        return con ?: throw Exception("Database not enabled!")
    }

    fun getMongoCollection(): MongoCollection<Document> {
        return mongoCollection ?: throw Exception("MongoDB not enabled!")
    }

}

fun main(args: Array<String>) {
    if(args.size > 1) {
        println("Usage: java -jar DynmapFetcher.jar <url>")
        return
    }
    val localConfig = Configuration()
    if(!Files.exists(Paths.get("config.json"))) {
        Files.writeString(Paths.get("config.json"), Main::class.java.getResourceAsStream("/config.json").bufferedReader().readText())
    }
    localConfig.load(Paths.get("config.json"))
    var url = (if(args.size == 1) args[0] else localConfig.getConfig()["fetcher"].asJsonObject["url"].asString)
    if(url.endsWith("/")) {
        url = url.substring(0, url.length - 1)
    }
    Main(url, localConfig).init()
}