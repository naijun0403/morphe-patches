import com.android.build.api.dsl.ApplicationExtension
import java.net.URL

plugins {
    alias(libs.plugins.protobuf)
}

val sherpaOnnxVersion = "1.10.46"
val sherpaOnnxAar = File(rootProject.rootDir, ".gradle/sherpa-onnx-$sherpaOnnxVersion.aar")

val downloadSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx AAR from GitHub releases."
    onlyIf { !sherpaOnnxAar.exists() }
    doLast {
        sherpaOnnxAar.parentFile.mkdirs()
        val tmp = File(sherpaOnnxAar.parentFile, "sherpa-onnx.tmp.aar")
        try {
            URL(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download" +
                        "/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
            ).openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            check(tmp.renameTo(sherpaOnnxAar)) { "Could not move downloaded AAR" }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))

    implementation(libs.collections4)
    implementation(libs.lang3)
    implementation(libs.protobuf.javalite)
    implementation(libs.commons.compress)
    implementation(files(sherpaOnnxAar) { builtBy(downloadSherpaOnnx) })
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
