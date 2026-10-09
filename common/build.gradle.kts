plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}

// TiPlay: the phone serves a copy of the browser page (site/play/) to browsers that cannot use the HTTPS page
// (TeslaBrowserLink, protocol §7.2). The copy is generated from the page sources at build time, into assets/play/;
// never commit one under src/.
abstract class BundleBrowserPage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:javax.inject.Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun bundle() {
        files.sync {
            from(page)
            into(outputDirectory.dir("play"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val bundle = tasks.register<BundleBrowserPage>("bundle${variant.name.replaceFirstChar(Char::uppercaseChar)}BrowserPage") {
            page.set(rootProject.layout.projectDirectory.dir("site/play"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleBrowserPage::outputDirectory)
    }
}
