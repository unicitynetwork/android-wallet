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
    // Unicity Nostr SDK (includes Nostr client, crypto, nametag binding, token transfer)
    // Using composite build from sibling directory
    implementation("org.unicitylabs:unicity-nostr-sdk:1.0.0")

    // Unicity SDK
    implementation("com.github.unicitynetwork:java-state-transition-sdk:1.2.0")

    // Jackson for JSON and CBOR (also used by Nostr SDK)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // CLI argument parsing
    implementation("info.picocli:picocli:4.7.5")

    // BIP-39 for mnemonic phrase (pure Java)
    implementation("org.bitcoinj:bitcoinj-core:0.16.3")

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
