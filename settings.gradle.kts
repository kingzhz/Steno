pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android 只发布在 JitPack（Maven Central 上没有：
        // cz.adaptech.tesseract4android 的 pom 实测 404）。
        //
        // 坐标是 com.github.adaptech-cz:Tesseract4Android，但它是个多模块工程：
        // 那个 POM 只是个壳，真正装的是子模块
        //   group = com.github.adaptech-cz.Tesseract4Android  （点号！）
        //   artifact = tesseract4android
        // 所以过滤器必须按前缀放行 —— 写成 includeGroup("com.github.adaptech-cz")
        // 会把子模块的 group 挡在外面，报「Could not find
        // com.github.adaptech-cz.Tesseract4Android:tesseract4android」。
        // 这个坑实测踩过一次。
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.adaptech-cz.*") }
        }
    }
}

rootProject.name = "SNOTE"
include(":app")
