plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-netty:2.3.0")
    implementation("io.ktor:ktor-server-html-builder-jvm:2.3.0")
    implementation("io.ktor:ktor-server-sessions:2.3.0")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.0")
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.8.0")
}

kotlin {
    jvmToolchain(17) // Используем JVM toolchain для Java 11 (можно 17)
}

application {

    mainClass.set("MainKt")
}


