import java.io.File

fun buildProject(name: String, filePaths: List<String>)
{
    val modules = mutableListOf<Module>()

    for (path in filePaths)
    {
        val module = Module(File(path))

        Parser(module).parse()

        module.registerSymbols()

        modules += module
    }

    // Recursively handle imports
    modules[0].handleImports("", modules)

    for (module in modules)
    {
        Checker(module).check()
    }

    val irFilePaths = mutableListOf<String>()
    for (module in modules)
    {
        irFilePaths += Generator(module).generate()
    }

    val clangArgs = mutableListOf(
        "clang",
        "-Wno-unused-value",
        "-Wno-parentheses-equality",
        "-Wno-string-compare",
        "-o",
        name,
        "stdlib.c",
    )

    clangArgs += irFilePaths

    val clangProcess = ProcessBuilder(clangArgs)
        .inheritIO()
        .start()

    clangProcess.waitFor()
}

fun runProject(name: String)
{
    val userProcess = ProcessBuilder("./" + name)
        .inheritIO()
        .start()

    userProcess.waitFor()

    println("User process finished with exit code ${userProcess.exitValue()}")
}
