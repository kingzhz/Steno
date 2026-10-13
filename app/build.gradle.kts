import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * release 签名配置。
 *
 * ## 为什么用 if (file.exists()) 包起来
 *
 * keystore.properties 不进仓库（含密码），密钥文件甚至放在项目目录之外。
 * 于是「克隆这个仓库的人」手上没有它 —— 若这里无条件读取，他会连
 * assembleDebug 都跑不起来，而他根本不需要签名就能开发。
 *
 * 所以：有配置就签，没有就跳过 signingConfig。跳过时 assembleRelease 产出
 * 未签名包（装不上），但至少不会让项目的其余部分崩掉。
 *
 * ## 密码不进仓库这件事本身
 *
 * keystore.properties 只放路径与密码，实体在 D:/SNOTE-keys/。
 * 双层防护：即使 .gitignore 被改错，密钥也不在仓库目录树内。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() && keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.snote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只有拿到密钥时才绑定；否则产出未签名包。
            //
            // 为什么这很重要：签名一旦换过，已装用户必须「卸载」才能升级，
            // 而卸载会删掉应用私有目录 —— 对这个 App 意味着笔记全没。
            // 所以第一次公开发版就必须用这个正式密钥，不能先发 debug 包再换。
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // PDF 文字层提取。约 3-5 MB，选它的理由见 libs.versions.toml 的注释
    // （许可证 Apache-2.0；本项目要开源，不能用 AGPL 的 iText）。
    implementation(libs.pdfbox.android)

    // OCR 引擎（Apache-2.0）。自带 Tesseract 的 JNI 库；语言模型另外放 assets。
    //
    // 必须排除 openmp 变体：该 POM 同时声明了 tesseract4android 与
    // tesseract4android-openmp 两个 artifact，而它们各自带一份
    // cz.adaptech.tesseract4android.BuildConfig —— 一起进来会报
    // "Duplicate class ... BuildConfig found in modules"。实测踩过。
    //
    // 选不带 openmp 的那个：多线程识别对单张图片收益很小，却多背一份原生库。
    implementation(libs.tesseract4android) {
        exclude(group = "com.github.adaptech-cz.Tesseract4Android", module = "tesseract4android-openmp")
    }

    // 只在 debug 变体引入：Preview 渲染需要它，但 release 包不需要，
    // 用 debugImplementation 可以避免把它打进正式包。
    debugImplementation(libs.androidx.compose.ui.tooling)
}
