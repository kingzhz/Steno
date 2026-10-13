# release 混淆规则。
#
# 骨架阶段原本只有一句「无需额外规则」，但打开 isMinifyEnabled 后
# R8 会把「编译期存在、运行时可选」的类当成真缺失。

# —— PdfBox-Android 的 JPEG2000 可选依赖 ——
#
# 报错原文：
#   R8: Missing class com.gemalto.jp2.JP2Decoder (referenced from:
#   com.tom_roush.pdfbox.filter.JPXFilter.readJPX(...))
#
# JPXFilter 处理的是 PDF 里的 JPEG2000 图像，需要的 jp2-android 库
# PdfBox-Android 把它标成可选（不引入也能用其余功能）。R8 看不到这个依赖，
# 就认为类丢了并中断构建。
#
# 用 -dontwarn 而不是引入 jp2-android：本项目的 PDF 只做**文字层提取**，
# 不需要解码 JPEG2000 图片，为此多背一个原生库不划算。
# 万一真遇到 JPX 图像，这一条会让它走异常分支而不是崩在找不到类上。
-dontwarn com.gemalto.jp2.**

# Tesseract 的 JNI 层通过 native 反射调用，混淆后方法名对不上会运行时崩溃。
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
