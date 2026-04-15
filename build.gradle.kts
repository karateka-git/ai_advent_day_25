import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

val cliAppName = "local-document-indexer"

application {
    mainClass = "ru.compadre.indexer.AppKt"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.named<CreateStartScripts>("startScripts") {
    applicationName = cliAppName
    defaultJvmOpts = application.applicationDefaultJvmArgs
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
