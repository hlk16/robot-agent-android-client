pluginManagement {
    repositories {
        // 阿里云镜像源 - 加速插件下载
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像源 - 加速依赖下载
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven{
            url=uri( "https://oss.sonatype.org/content/groups/public/")
        }
        maven{
            url=uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
    }
}

rootProject.name = "xiaozhi"
include(":app")
include(":sdk")
