// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("com.github.node-gradle.node") version "7.1.0"
}

// Configure the node environment for the whole project.
node {
    // Version of Node.js to use. The plugin will download and manage it.
    version.set("20.14.0")

    distBaseUrl = null

    // Let the plugin manage the download.
    download.set(true)
    // Set the directory where your package.json is located.
    nodeProjectDir.set(file("${project.projectDir}/js-sdk-bundler"))
}

// Define the task that runs the Webpack build.
tasks.register<com.github.gradle.node.npm.task.NpmTask>("bundleJsSdk") {
    group = "build"
    description = "Uses Webpack to bundle the Unicity JS SDK for the WebView."

    // This task depends on the automatic 'npm install' task from the plugin.
    dependsOn(tasks.named("npmInstall"))

    // Corresponds to the "build": "webpack" script in your js-sdk-bundler/package.json
    npmCommand.set(listOf("run", "build"))
    
    // Configure inputs and outputs for incremental builds
    inputs.files(
        fileTree("js-sdk-bundler/src"),
        file("js-sdk-bundler/package.json"),
        file("js-sdk-bundler/package-lock.json"),
        file("js-sdk-bundler/webpack.config.js"),
        file("js-sdk-bundler/tsconfig.json")
    )
    
    outputs.file("app/src/main/assets/unicity-sdk.js")
    
    // Only run if inputs have changed
    outputs.upToDateWhen { true }
}
