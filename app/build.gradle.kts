plugins {
    kotlin("multiplatform") version "2.4.20"
}

group = "em10"
version = "0.1.0"

kotlin {
    jvm {
        binaries {
            executable {
                mainClass.set("em10.cli.MainKt")
            }
        }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// A self-contained Linux program: native launcher plus a trimmed Java runtime, no Java needed on the
// target machine. Output: build/linux/em10/bin/em10 (and build/linux/em10-linux-x86_64.tar.gz).
val linuxApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds build/linux/em10, a Linux program with its own Java runtime"
    dependsOn("installJvmDist")
    val lib = layout.buildDirectory.dir("install/em10-jvm/lib").get().asFile
    val out = layout.buildDirectory.dir("linux").get().asFile
    val jar = "em10-jvm-${project.version}.jar"
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage").path
    doFirst { out.deleteRecursively() }
    commandLine(
        jpackage, "--type", "app-image", "--name", "em10", "--app-version", project.version.toString(),
        "--input", lib.path, "--main-jar", jar, "--main-class", "em10.cli.MainKt",
        "--add-modules", "java.base,java.net.http",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages",
        "--dest", out.path,
    )
    doLast {
        ProcessBuilder("tar", "czf", "em10-linux-x86_64.tar.gz", "em10").directory(out).inheritIO().start().waitFor()
    }
}
