import kotlin.system.exitProcess

const val RESET = "\u001B[0m"
const val RED = "\u001B[31m"
const val GREEN = "\u001B[32m"

fun reportCompilation(name: String)
{
    println("${GREEN}Compiling file${RESET}: $name")
}

fun reportError(stage: String, pos: FilePos, msg: String): Nothing
{
    println("${RED}Error[$stage] (${pos.line}:${pos.column})${RESET}: $msg")
    exitProcess(0)
}

class InternalCompilerException(message: String) : Exception(message)
