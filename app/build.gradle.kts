import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val generatedFormatDir = layout.buildDirectory.dir("generated/documentFormats/kotlin").get().asFile

val generateDocumentFormats by tasks.registering {
    notCompatibleWithConfigurationCache("The source scanner is implemented in the build script")
    inputs.files(fileTree("src/main/java") { include("**/*.kt") })
    outputs.dir(generatedFormatDir)
    doLast {
        val outputDir = generatedFormatDir
        val packageDir = File(outputDir, "top/lighilit/watch_data_sync")
        packageDir.mkdirs()
        val backendPattern = Regex("@DocumentBackend\\s+(?:internal\\s+)?(class|object)\\s+(\\w+)\\s*:")
        val backends = fileTree("src/main/java")
            .matching { include("**/*.kt") }
            .files
            .flatMap { backendPattern.findAll(it.readText()).map { match -> match.groupValues[1] to match.groupValues[2] }.toList() }
            .distinct()
            .sortedBy { it.second }
        require(backends.isNotEmpty()) { "No @DocumentBackend implementations found" }
        File(packageDir, "GeneratedDocumentFormats.java").writeText(
            "package top.lighilit.watch_data_sync;\n\n" +
                "final class GeneratedDocumentFormats {\n" +
                "    static java.util.List<DocumentFormat> all() {\n" +
                "        return java.util.Arrays.asList(\n" +
                backends.joinToString(",\n") { (kind, name) ->
                    if (kind == "object") "            $name.INSTANCE" else "            new $name()"
                } +
                "\n        );\n    }\n}\n"
        )
    }
}

android.sourceSets["main"].java.directories.add(generatedFormatDir.absolutePath)
tasks.named("preBuild") { dependsOn(generateDocumentFormats) }

android {
    namespace = "top.lighilit.watch_data_sync"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "top.lighilit.watch_data_sync"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use(::load)
        }
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("sharedRelease") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("sharedRelease")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
