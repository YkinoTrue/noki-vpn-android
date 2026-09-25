import java.io.File
import java.security.MessageDigest

plugins {
    base
}

val pinnedNdk = "29.0.14206865"
val buildSpecs = listOf(
    Triple("arm64-v8a", "arm64", "aarch64-linux-android26"),
    Triple("armeabi-v7a", "arm", "armv7a-linux-androideabi26"),
    Triple("x86_64", "amd64", "x86_64-linux-android26"),
)

val nativeBuilds = buildSpecs.map { (abi, goArch, target) ->
    tasks.register<Exec>("buildNative${abi.replace("-", "").replaceFirstChar(Char::uppercase)}") {
        val output = layout.buildDirectory.file("jniLibs/$abi/libnoki_wireguard.so")
        inputs.files(fileTree(projectDir) { include("*.go", "*.c", "go.mod", "go.sum") })
        outputs.file(output)
        workingDir = projectDir
        executable = providers.environmentVariable("NOKI_GO_BIN").orElse("go").get()
        args("build", "-trimpath", "-buildmode=c-shared",
             "-o", output.get().asFile.absolutePath, ".")
        environment("GOOS", "android")
        environment("GOARCH", goArch)
        environment("CGO_ENABLED", "1")
        if (goArch == "arm") environment("GOARM", "7")
        doFirst {
            val sdk = providers.environmentVariable("ANDROID_HOME")
                .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
                .orNull ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
            val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else "linux-x86_64"
            val ndk = File(sdk, "ndk/$pinnedNdk/toolchains/llvm/prebuilt/$host")
            val clang = File(ndk, "bin/clang${if (host.startsWith("windows")) ".exe" else ""}")
            require(clang.isFile) { "Pinned NDK $pinnedNdk missing: $clang" }
            val flags = "--target=$target --sysroot=${File(ndk, "sysroot").absolutePath.replace('\\', '/')}"
            environment("CC", clang.absolutePath)
            environment("CGO_CFLAGS", flags)
            environment("CGO_LDFLAGS", flags)
            environment("PATH", File(ndk, "bin").absolutePath + File.pathSeparator + System.getenv("PATH"))
            output.get().asFile.parentFile.mkdirs()
        }
    }
}

tasks.register("buildNative") {
    group = "build"
    description = "Build the pinned upstream WireGuard Android bridge for all shipped ABIs"
    dependsOn(nativeBuilds)
}

tasks.register("verifyNative") {
    group = "verification"
    description = "Verify WireGuard ELF ABI/JNI exports and record artifact hashes"
    dependsOn(nativeBuilds)
    doLast {
        val sdk = providers.environmentVariable("ANDROID_HOME")
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
            .orNull ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
        val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else "linux-x86_64"
        val readelf = File(sdk, "ndk/$pinnedNdk/toolchains/llvm/prebuilt/$host/bin/llvm-readelf${if (host.startsWith("windows")) ".exe" else ""}")
        require(readelf.isFile) { "Pinned NDK readelf missing: $readelf" }
        val machines = mapOf(
            "arm64-v8a" to "AArch64",
            "armeabi-v7a" to "ARM",
            "x86_64" to "Advanced Micro Devices X86-64",
        )
        val requiredExports = listOf(
            "Java_com_noki_vpn_vpn_NativeWireGuard_startNative",
            "Java_com_noki_vpn_vpn_NativeWireGuard_stopNative",
            "Java_com_noki_vpn_vpn_NativeWireGuard_statsNative",
            "Java_com_noki_vpn_data_NativeWireGuardKeys_generateNative",
        )
        val hashes = buildSpecs.map { (abi, _, _) ->
            val binary = layout.buildDirectory.file("jniLibs/$abi/libnoki_wireguard.so").get().asFile
            require(binary.isFile) { "Missing native artifact for $abi" }
            val header = ProcessBuilder(readelf.absolutePath, "-h", binary.absolutePath)
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            require(header.contains("Machine:") && header.contains(machines.getValue(abi))) {
                "Unexpected ELF machine for $abi"
            }
            val symbols = ProcessBuilder(readelf.absolutePath, "--dyn-syms", binary.absolutePath)
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            requiredExports.forEach { symbol ->
                require(symbols.contains(symbol)) { "$abi lacks JNI export $symbol" }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            "$hash  $abi/libnoki_wireguard.so"
        }
        val manifest = layout.buildDirectory.file("native-sha256.txt").get().asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(hashes.joinToString("\n", postfix = "\n"))
    }
}
