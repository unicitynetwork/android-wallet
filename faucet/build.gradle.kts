plugins {
    java
    application
}

group = "org.unicitylabs"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Unicity SDK
    implementation("com.github.unicitynetwork:java-state-transition-sdk:1.2.0")

    // Jackson for JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.0")

    // OkHttp for WebSocket (Nostr relay)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // BouncyCastle for Schnorr signatures (BIP-340) - pure Java crypto
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Hex encoding
    implementation("commons-codec:commons-codec:1.16.0")

    // CLI argument parsing
    implementation("info.picocli:picocli:4.7.5")

    // BIP-39 for mnemonic phrase (pure Java)
    implementation("org.bitcoinj:bitcoinj-core:0.16.3")

    // Phone number normalization for deterministic nametag hashing
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.51")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

application {
    mainClass.set("org.unicitylabs.faucet.FaucetCLI")
}

tasks.register<JavaExec>("mint") {
    group = "application"
    description = "Mint and send a token via Nostr"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.unicitylabs.faucet.FaucetCLI")

    // Allow passing arguments: ./gradlew mint --args="--nametag=alice --amount=100"
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split("\\s+".toRegex())
    }
}
