package de.neo.dynmapfetcher

import com.google.gson.*
import org.bson.Document
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.net.ssl.HttpsURLConnection
import kotlin.math.log
import kotlin.math.roundToInt

class Fetcher(private val urlString: String, private val localFetcherConfig: JsonObject, private val localConfig: JsonObject) : Runnable {

    private val time: Long

    private val logger = Logger.getLogger("Fetcher")
    private val fileBuffer: HashMap<String, String> = HashMap()
    private var lastMap = StringBuffer()
    private var bufferMaps = false
    private val debug = localConfig["debug"].asBoolean
    private val shouldFetchTiles = localFetcherConfig["fetchTiles"].asBoolean
    private val updateTime =
        if(localFetcherConfig["fetchingConfig"].asJsonObject["override"].asBoolean)
            localFetcherConfig["fetchingConfig"].asJsonObject["interval"].asLong
        else
            Main.getInstance().getConfig().getConfig()["updaterate"].asDouble.toLong()

    init {
        time = System.currentTimeMillis()
    }

    override fun run() {
        var i = 0
        var retryTime = 0L
        var fetchTime = 0L
        var json: String
        var url: URL
        var parsed: JsonObject
        var fileName: String
        var con: HttpURLConnection
        while(true) {
            try {
                url = URL(urlString.replace("%s", "up/world/world/${System.currentTimeMillis()/1000}"))
                con = if(url.protocol == "https") {
                    url.openConnection() as HttpsURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }
                json = con.inputStream.bufferedReader().readText()
                con.disconnect()
                lastMap.delete(0, lastMap.length)
                lastMap.append(json)
                fileName = "${System.currentTimeMillis()/1000}.json"

                if(!localConfig["sql"].asJsonObject["replaceFiles"].asBoolean) {
                    Files.writeString(Path.of("mapData", fileName), json)
                }

                try {
                    Thread {
                        Main.getInstance().getMongoCollection().insertOne(Document.parse(json))
                    }.start()
                }catch (_: Exception) { }

                if(localConfig["mongodb"].asJsonObject["enabled"].asBoolean) {
                    try {
                        Thread {
                            Main.getInstance().getMongoCollection().insertOne(Document.parse(json))
                        }.start()
                    }catch (_: Exception) {
                        if(localConfig["sql"].asJsonObject["tempOnly"].asBoolean) {
                            try {
                                Thread {
                                    Main.getInstance().getDatabaseConnection().prepareStatement("INSERT INTO mapData (json) VALUES (?)").apply {
                                        setString(1, json)
                                        execute()
                                        close()
                                    }
                                }.start()
                            }catch (_: Exception) {
                                logger.warning("Could not insert into database!")
                                logger.warning("Writing to temp file!")
                                Files.writeString(Path.of("mapData", fileName), json)
                            }
                        }
                    }
                }

                if(!localConfig["sql"].asJsonObject["tempOnly"].asBoolean) {
                    try {
                        Thread {
                            Main.getInstance().getDatabaseConnection().prepareStatement("INSERT INTO mapData (json) VALUES (?)").apply {
                                setString(1, json)
                                execute()
                                close()
                            }
                        }.start()
                    }catch (_: Exception) {
                        logger.warning("Could not insert into database!")
                        logger.warning("Writing to temp file!")
                        Files.writeString(Path.of("mapData", fileName), json)
                    }
                }

                if(bufferMaps) {
                    fileBuffer[fileName] = json
                }
                try {
                    parsed = Gson().fromJson(json, JsonObject::class.java)
                    if(i % localFetcherConfig["checkPlayersTime"].asInt == 0) {
                        logger.info("Checking for player count...")
                        if(parsed["players"].asJsonArray.size() == 0) {
                            if(fetchTime != Main.getInstance().getConfig().getConfig()["updaterate"].asDouble.toLong()*10) {
                                fetchTime = Main.getInstance().getConfig().getConfig()["updaterate"].asDouble.toLong()*10
                                logger.warning("No players online. reducing fetch time to ${fetchTime/1000}s")
                            }
                        } else {
                            if(fetchTime != Main.getInstance().getConfig().getConfig()["updaterate"].asDouble.toLong()) {
                                fetchTime = Main.getInstance().getConfig().getConfig()["updaterate"].asDouble.toLong()
                                logger.warning("Players online. increasing fetch time to ${fetchTime/1000}s")
                            }
                        }
                    }
                    if(parsed["players"].asJsonArray.size() > 0) {
                        fetchTiles(parsed["players"].asJsonArray)
                    }
                }catch (e: JsonParseException) {
                    logger.warning("Failed to parse json: $json")
                    parsed = JsonObject()
                }
                Thread{fetchTiles(json)}.start()
                Thread.sleep(fetchTime)
                i++
                retryTime = 0
                logger.info("Fetched $i times")
            }catch (e: IOException) {
                retryTime += localFetcherConfig["retryTime"].asLong * 1000L
                logger.severe("Failed to fetch map data: ${e.message}")
                logger.warning("Retrying in ${retryTime/1000}s")
                Thread.sleep(retryTime)
            }
        }
    }

    fun fetchTiles(players: JsonArray) {
        for(playerObj in players) {
            val player = playerObj.asJsonObject
            val world = player["world"].asString
            if(world == "-some-other-bogus-world-") continue
            val x = ((player["x"].asInt / 32.0) + 0.5).roundToInt()
            val z = ((player["z"].asInt / 32.0) + 0.5).roundToInt()
            val nameFlat =
                "$world/flat/${((x / 32.0) + 0.5).roundToInt()}_${((z / 32.0) + 0.5).roundToInt()}/${x}_$z.png"
            val name3D =
                "$world/t/${((x / 32.0) + 0.5).roundToInt()}_${((z / 32.0) + 0.5).roundToInt()}/${x}_$z.png"
            if(debug) println("try to fetch $nameFlat")
            fetchTile(nameFlat)
            if(debug) println("try to fetch $name3D")
            fetchTile(name3D)
        }
    }

    fun fetchTile(name: String) {
        if(!shouldFetchTiles) return
        val tilePath = Path.of("tiles/$time", name)
        if(Files.exists(tilePath)) {
            return
        }
        if(!Files.exists(tilePath.parent)) {
            Files.createDirectories(tilePath.parent)
        }
        logger.info("Fetching tile $name")
        val url = URL(urlString.replace("%s", "tiles/$name"))
        val con = if(url.protocol == "https") {
            url.openConnection() as HttpsURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        val tile = ImageIO.read(con.inputStream)
        con.disconnect()
        ImageIO.write(tile, "jpg", File("tiles/$time/$name"))
    }

    fun fetchTiles(json: String) {
        try {
            val parsed = Gson().fromJson(json, JsonObject::class.java)
            val updates = parsed["updates"].asJsonArray
            var updateObject: JsonObject
            var type: String
            var name: String
            for(update in updates) {
                updateObject = update.asJsonObject
                type = updateObject["type"].asString
                if(type == "tile") {
                    name = URLDecoder.decode(updateObject["name"].asString, "UTF-8")
                    fetchTile("world/$name")
                }
            }
        }catch (e: JsonParseException) {
            logger.warning("Failed to parse json: $json")
        }
    }

    fun getMap(): StringBuffer {
        return lastMap
    }

    fun export(out: OutputStream) {
        val time = System.currentTimeMillis();
        out.write("Packing map data...".toByteArray())
        out.flush()
        Runtime.getRuntime().exec("tar -cvf mapData_$time.tar mapData")
        out.write("Done!\n".toByteArray())
        out.write("Deleting map data...".toByteArray())
        out.flush()
        Runtime.getRuntime().exec("rm -rf mapData/*.json")
        out.write("Done!\n".toByteArray())
        out.write("Compressing map data...".toByteArray())
        out.flush()
        Runtime.getRuntime().exec("gzip -9 mapData_$time.tar")
        out.write("Done!\n".toByteArray())
        out.flush()

        // Recover
        for(dir in fileBuffer) {
            Files.writeString(Path.of("mapData", dir.key), dir.value)
        }
    }

}