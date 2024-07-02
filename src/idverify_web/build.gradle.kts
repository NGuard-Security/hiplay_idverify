repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

configurations { create("externalLibs") }

plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.0.0"

    id("org.springframework.boot") version "3.3.1"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    implementation("io.viascom.nanoid:nanoid:1.0.1")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("org.mongodb:mongodb-driver-sync:5.1.1")

    implementation("com.github.toss:toss-cert-java-sdk:0.0.14")
    implementation(files("$projectDir/libs/CtCli-1.0.7.jar"))
}
