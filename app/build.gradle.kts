plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.hm02.mail"
  compileSdk = 35
  defaultConfig {
    applicationId = "dev.hm02.mail"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":hm02-core"))
}
