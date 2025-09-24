pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // —— 仅存放 AMap 相关产物
        maven("https://maven.amap.com/repository/releases") {
            // 关键 ❶：显式只允许 AMap 自己的 group
            content { includeGroupByRegex("com\\.amap\\..*") }
        }

        // 华为仓库
        maven("https://developer.huawei.com/repo/") {
            content {
                includeGroup("com.huawei.hms")
                includeGroup("com.huawei.android.hms")
                includeGroup("com.huawei.hmf")
                includeGroup("com.huawei.agconnect")
            }
        }

        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {

    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)  // 👈 加这一行，允许项目级 repositories
    repositories {
        google(); mavenCentral()

        maven("https://maven.amap.com/repository/releases") {
            // 关键 ❷：同样只让它解析 AMap 自家产物
            content { includeGroupByRegex("com\\.amap\\..*") }
        }
        flatDir {
            dirs("app/libs")
        }

        maven("https://developer.huawei.com/repo/") {
            content {
                includeGroup("com.huawei.hms")
                includeGroup("com.huawei.android.hms")
                includeGroup("com.huawei.hmf")
                includeGroup("com.huawei.agconnect")
            }
        }

        maven("https://jitpack.io")
    }
}

rootProject.name = "walkpromote22"
include(":app")
