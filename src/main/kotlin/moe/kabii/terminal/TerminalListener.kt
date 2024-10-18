package moe.kabii.terminal

import kotlinx.coroutines.launch
import moe.kabii.LOG
import moe.kabii.command.params.TerminalParameters
import moe.kabii.instances.DiscordInstances
import moe.kabii.util.extensions.applicationLoop
import moe.kabii.util.extensions.stackTraceString

class TerminalListener(val discord: DiscordInstances) : Runnable {
    private var active = false

    fun launch() {
        check(!active) { "TerminalListener thread already launched" }
        val thread = Thread(this, "TerminalListener")
        thread.start()
        active = true
    }

    override fun run() {
        val manager = discord.manager
        applicationLoop {
            val line = readlnOrNull()
            if(line.isNullOrBlank()) return@applicationLoop
            manager.context.launch {

                // no prefix, etc. just match first arg as command name
                val msgArgs = line.split(" ")
                    .filter(String::isNotBlank)
                val cmdStr = msgArgs[0]

                val command = manager.commandsTerminal[cmdStr.lowercase()]

                if(command == null) {
                    if(manager.commands.find { cmd -> cmd.name == cmdStr.lowercase() } != null) {
                        println("Command not supported for terminal usage.")
                    } else println("Command not found.")
                    return@launch
                }

                val args = msgArgs.drop(1)
                val noCmd = line.substring(cmdStr.length - 1)
                val param = TerminalParameters(discord, noCmd, args)

                try {
                    command.executeTerminal!!(param)
                } catch(e: Exception) {
                    LOG.error("Uncaught exception in terminal command \"${command.name}\"\nErroring command: $line")
                    LOG.warn(e.stackTraceString)
                }
            }
        }
    }
}