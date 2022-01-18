package de.neo.dynmapfetcher

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class DynmapTelnet(port: Int) {

    private val socket = ServerSocket(port)

    fun start() {
        while (true) {
            println("Waiting for connection on port")
            val client = socket.accept()
            println("Accepted connection from ${client.inetAddress}")
            Thread {
                val input = client.getInputStream()
                val output = client.getOutputStream()
                output.write("Welcome to the dynmapper telnet server\n".toByteArray())
                output.write("Type 'help' for help\n".toByteArray())
                output.write("Type 'exit' to exit\n".toByteArray())
                output.flush()

                while(true) {
                    val command = input.bufferedReader().readLine().split(" ")
                    val cmd = command[0]
                    if (cmd == "get") {
                        output.write("OK\n".toByteArray())
                        try {
                            output.write(Main.getInstance().getFetcher().getMap().toString().toByteArray())
                        }catch (e: Exception) {
                            output.write("\nERROR: ${e.message}\n".toByteArray())
                        }
                        output.write("\n".toByteArray())
                    } else if(cmd == "export") {
                        output.write("OK\n".toByteArray())
                        try {
                            Main.getInstance().getFetcher().export(output)
                        }catch (e: Exception) {
                            output.write("\nERROR: ${e.message}\n".toByteArray())
                        }
                        output.write("Exported ${Files.list(Path.of("mapData")).toArray().size} files\n".toByteArray())
                    } else if(cmd == "help") {
                        output.write("OK\n".toByteArray())
                        output.write("get - get map data\n".toByteArray())
                        output.write("export - export map data\n".toByteArray())
                        output.write("players - get player list\n".toByteArray())
                        output.write("exit - exit\n".toByteArray())
                    } else if(cmd == "exit") {
                        output.write("BYE\n".toByteArray())
                        break
                    } else if(cmd == "players") {
                        output.write("OK\n".toByteArray())
                        output.write("Player list:\n".toByteArray())
                        try {
                            val players = Gson().fromJson(Main.getInstance().getFetcher().getMap().toString(), JsonObject::class.java)
                            output.write("NAME                         X      Y      Z      HEALTH\n".toByteArray())
                            for(player in players["players"].asJsonArray) {
                                val playerData = player.asJsonObject
                                val name = playerData["name"].asString
                                val x = playerData["x"].asDouble
                                val y = playerData["y"].asDouble
                                val z = playerData["z"].asDouble
                                val health = playerData["health"].asDouble
                                output.write(("$name${" ".repeat(29 - name.length)}" +
                                        "$x${" ".repeat(7 - x.toString().length)}" +
                                        "$y${" ".repeat(7 - y.toString().length)}" +
                                        "$z${" ".repeat(7 - z.toString().length)}" +
                                        "$health\n").toByteArray())
                            }
                        }catch (e: Exception) {
                            output.write("\nERROR: ${e.message}\n".toByteArray())
                        }
                    } else {
                        output.write("ERROR\n".toByteArray())
                        output.write("Unknown command: $command\n".toByteArray())
                    }
                    output.flush()
                }
                output.close()

                client.close()
            }.start()
        }
    }

}