
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)  // 使用 Version Catalog
    id("org.jetbrains.kotlin.android") version "1.9.0" apply true
    id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.0" apply true



}
// 读取 local.properties（若不存在则回退到环境变量/空串）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val mastoToken: String = (localProps.getProperty("MASTO_TOKEN")
    ?: System.getenv("MASTO_TOKEN") ?: "").trim()

android {
    namespace = "com.example.walkpromote22"
    compileSdk = 35

    buildFeatures { buildConfig = true } // 库模块需要
    defaultConfig {

        applicationId = "com.example.walkpromote22"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"



        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MASTO_TOKEN", "\"${mastoToken}\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}



dependencies {

    implementation(files("libs/samsung-health-data-1.5.1.aar"))
    implementation(libs.gson)



    implementation("com.amap.api:search:7.3.0")

    // https://mvnrepository.com/artifact/ch.hsr/geohash
    implementation("ch.hsr:geohash:1.3.0")




    // When using the BoM, you don't specify versions in Firebase library dependencies




    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
    implementation ("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation ("com.github.PhilJay:MPAndroidChart:v3.1.0")


    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("com.squareup.retrofit2:converter-scalars:2.9.0")

    implementation ("androidx.work:work-runtime:2.7.1")
    implementation ("androidx.room:room-runtime:2.4.3")
    implementation ("com.squareup.okhttp3:okhttp:4.9.3")
    implementation ("com.squareup.okhttp3:logging-interceptor:4.9.3")
    implementation ("com.google.android.gms:play-services-auth:20.5.0")
    implementation ("com.google.android.gms:play-services-fitness:20.0.0")
    implementation ("androidx.cardview:cardview:1.0.0")

    implementation ("commons-io:commons-io:2.11.0")
    implementation ("com.google.android.material:material:1.6.0")


    // 3D地图SDK基础功能
    // https://mvnrepository.com/artifact/com.amap.api/navi-3dmap
    // https://mvnrepository.com/artifact/com.amap.api/navi-3dmap

    implementation ("com.amap.api:3dmap:9.8.2")
    implementation("com.amap.api:map2d:5.2.0")
  //  implementation("com.amap.api:search:9.8.0")
// 定位SDK核心功能
    //implementation ("com.amap.api:location:6.4.9")


    //华为fit

    implementation(libs.health)
    implementation(libs.hwid)
 // ✅ new SDK


    //小米
    /*implementation (libs.androidx.connect.client){
        exclude(group = "androidx.health.connect", module = "connect-client-proto")
    }*/

    implementation (libs.androidx.room.runtime)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.room.common.jvm)
    // implementation(files("libs\\navi-3dmap-9.3.0_3dmap9.3.0.jar"))
    annotationProcessor ("androidx.room:room-compiler:$2.52") // 对于 Java 项目



    // Add the dependencies for the Crashlytics and Analytics libraries
    // When using the BoM, you don't specify versions in Firebase library dependencies


    annotationProcessor ("androidx.room:room-compiler:2.4.3")
    implementation ("androidx.core:core-ktx:1.10.1")
    implementation ("androidx.core:core:1.15.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)



    implementation ("androidx.credentials:credentials:1.3.0-alpha01")

    implementation ("androidx.credentials:credentials-play-services-auth:1.3.0-alpha01")

    implementation ("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    implementation ("androidx.appcompat:appcompat:1.3.1")

    implementation ("com.google.android.gms:play-services-auth:21.1.1")

    // Room 依赖

    annotationProcessor ("androidx.room:room-compiler:2.7.0-rc01")

// 强制匹配 SQLite 依赖版本
    implementation ("androidx.sqlite:sqlite:2.3.1")
    implementation ("androidx.sqlite:sqlite-framework:2.3.1")

}

