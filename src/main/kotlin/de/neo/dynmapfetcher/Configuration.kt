package de.neo.dynmapfetcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.HttpsURLConnection

class Configuration {

    private var config = JsonObject()

    fun fetch(url: String) {
        val url = URL(url.replace("%s", "up/configuration"))
        val con = if(url.protocol == "https") {
            url.openConnection() as HttpsURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        config = Gson().fromJson(con.inputStream.bufferedReader().readText(), JsonObject::class.java)
    }

    fun load(path: Path) {
        config = Gson().fromJson(Files.readString(path), JsonObject::class.java)
    }

    fun getConfig(): JsonObject {
        return config
    }

}