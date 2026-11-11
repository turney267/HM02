plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  id("maven-publish")
}

group = "com.github.turney267"
version = "1.1.0"

android {
  namespace = "dev.hm02.mail.core"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/NOTICE.md"
      excludes += "/META-INF/LICENSE.md"
      excludes += "/META-INF/NOTICE"
      excludes += "/META-INF/LICENSE"
    }
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  api(composeBom)
  androidTestImplementation(composeBom)

  api(libs.androidx.core.ktx)
  implementation(libs.androidx.webkit)
  api(libs.androidx.lifecycle.runtime.ktx)
  api(libs.androidx.activity.compose)
  api(libs.kotlinx.coroutines.android)
  api(libs.kotlinx.serialization.json)

  // Email Engine (Jakarta Mail / JavaMail for Android: IMAP & SMTP)
  api(libs.android.mail)
  api(libs.android.activation)

  api(libs.androidx.lifecycle.runtime.compose)
  api(libs.androidx.lifecycle.viewmodel.compose)

  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.ui.tooling.preview)
  api(libs.androidx.compose.material3)
  api(libs.androidx.compose.material.icons.core)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
  publishing {
    publications {
      register<MavenPublication>("release") {
        groupId = "com.github.turney267"
        artifactId = "hm02-core"
        version = "1.1.0"
        from(components["release"])
      }
    }
  }
}
