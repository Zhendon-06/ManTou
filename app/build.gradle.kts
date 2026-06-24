import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safe.args.kotlin)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hfad.mantou"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hfad.mantou"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 从 local.properties 读取 API Key 并注入到 BuildConfig
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }
        
        val apiKey = localProperties.getProperty("SILICONFLOW_API_KEY", "")
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true  // 启用 BuildConfig
    }
}

// 编译期扫描 tool/impl/ 下的 @MantouTool 注解，生成 LLM 用的 Markdown 文档。
// 写入 src/main/assets/mantou_tools.md，运行时 AppGenerator 从 assets 读取，
// 拼进 system prompt 给 LLM 看。AGP preBuild 会触发本任务，保证 APK 里始终是最新版。
val generateToolsDoc by tasks.registering {
    val sourceDir = layout.projectDirectory.dir("src/main/java/com/hfad/mantou/tool/impl").asFile
    val outputFile = layout.projectDirectory.file("src/main/assets/mantou_tools.md").asFile
    inputs.dir(sourceDir).withPropertyName("toolSource")
    outputs.file(outputFile).withPropertyName("toolsDoc")

    doLast {
        // 工具：把 Kotlin 源文件里的 \" 还原成原始引号，便于直接放进 markdown。
        fun unescape(raw: String): String = raw.replace("\\\"", "\"").replace("\\\\", "\\")

        // 把捕获到的字符串字面量（可能是 "a" + "b" + "c" 形式）拼接还原成原始内容。
        val partRegex = Regex(""""((?:\\.|[^"\\])*)"""")
        fun decode(rawLiteralGroup: String): String =
            partRegex.findAll(rawLiteralGroup).joinToString("") { unescape(it.groupValues[1]) }

        // 字符串字面量：单个 "..." 或多个 "..." + "..."，整体捕获，由 decode() 拆。
        val quotedString = """("(?:\\.|[^"\\])*"(?:\s*\+\s*"(?:\\.|[^"\\])*")*)"""
        // @MantouTool(name = "...", description = "...", usageScenario = "...")
        val mantouToolRegex = Regex(
            """@MantouTool\s*\(\s*name\s*=\s*$quotedString\s*,\s*description\s*=\s*$quotedString(?:\s*,\s*usageScenario\s*=\s*$quotedString)?\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        // @JavascriptInterface ... @ToolMethod(...) @ToolReturns(...) fun NAME(PARAMS): String
        val methodRegex = Regex(
            """@JavascriptInterface\s+@ToolMethod\s*\(\s*description\s*=\s*$quotedString(?:\s*,\s*example\s*=\s*$quotedString)?\s*\)\s*@ToolReturns\s*\(\s*description\s*=\s*$quotedString(?:\s*,\s*jsonExample\s*=\s*$quotedString)?\s*\)\s*fun\s+(\w+)\s*\(([\s\S]*?)\)\s*:\s*String""",
            RegexOption.DOT_MATCHES_ALL,
        )
        // @ToolParam(name = "...", description = "...") varName: Type
        val paramRegex = Regex(
            """@ToolParam\s*\(\s*name\s*=\s*$quotedString(?:\s*,\s*description\s*=\s*$quotedString)?\s*\)\s*(\w+)\s*:\s*(\w+)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val sb = StringBuilder()
        sb.appendLine("# 馒头 App 可用 Tools")
        sb.appendLine()
        sb.appendLine("> **编译期自动生成，请勿手动修改**。")
        sb.appendLine("> 来源: app/src/main/java/com/hfad/mantou/tool/impl/")
        sb.appendLine(">")
        sb.appendLine("> ## 调用约定")
        sb.appendLine("> ```js")
        sb.appendLine("> // 1. 先判断是否在馒头 App 中")
        sb.appendLine("> if (window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp()) {")
        sb.appendLine(">     // 2. 调用 Tool 方法（同步返回 JSON 字符串）")
        sb.appendLine(">     var rawJson = window.MantouApp.<toolName>.<methodName>(...args);")
        sb.appendLine(">     var r = JSON.parse(rawJson);")
        sb.appendLine(">     if (r.success) { /* 用 r.data */ } else { /* 提示 r.error */ }")
        sb.appendLine("> }")
        sb.appendLine("> ```")
        sb.appendLine(">")
        sb.appendLine("> 所有方法都返回 JSON 字符串：")
        sb.appendLine("> `{\"success\": bool, \"data\": any, \"error\": string|null}`")
        sb.appendLine(">")
        sb.appendLine("> **重要**：参数只能传基本类型（String / Int / Long / Boolean / Double），")
        sb.appendLine("> 不能传 JS 对象、数组或函数。")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        val files = sourceDir.listFiles { _, name -> name.endsWith(".kt") }?.sortedBy { it.name }
            ?: emptyArray<java.io.File>().toList()

        var toolCount = 0
        for (file in files) {
            val text = file.readText()
            val toolMatch = mantouToolRegex.find(text) ?: continue
            val toolName = decode(toolMatch.groupValues[1])
            val toolDesc = decode(toolMatch.groupValues[2])
            val toolScenario = decode(toolMatch.groupValues[3])

            sb.appendLine("## `$toolName`")
            sb.appendLine()
            sb.appendLine("**描述**：$toolDesc")
            if (toolScenario.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("**使用场景**：$toolScenario")
            }
            sb.appendLine()

            val methods = methodRegex.findAll(text).toList()
            if (methods.isEmpty()) {
                sb.appendLine("_（该 Tool 暂无对外方法）_")
                sb.appendLine()
            } else {
                for (m in methods) {
                    val methodDesc = decode(m.groupValues[1])
                    val methodExample = decode(m.groupValues[2])
                    val returnsDesc = decode(m.groupValues[3])
                    val returnsJson = decode(m.groupValues[4])
                    val methodName = m.groupValues[5]
                    val paramsBlock = m.groupValues[6]

                    val params = paramRegex.findAll(paramsBlock).map { p ->
                        val pName = decode(p.groupValues[1])
                        val pDesc = decode(p.groupValues[2])
                        val pType = p.groupValues[4]
                        Triple(pName, pType, pDesc)
                    }.toList()

                    val sig = params.joinToString(", ") { "${it.first}: ${it.second}" }
                    sb.appendLine("### `window.MantouApp.$toolName.$methodName($sig)` → String")
                    sb.appendLine()
                    sb.appendLine(methodDesc)
                    sb.appendLine()
                    if (params.isNotEmpty()) {
                        sb.appendLine("**参数**：")
                        for ((n, t, d) in params) sb.appendLine("- `$n` ($t)：$d")
                        sb.appendLine()
                    }
                    sb.appendLine("**返回**：$returnsDesc")
                    if (returnsJson.isNotBlank()) {
                        sb.appendLine()
                        sb.appendLine("```json")
                        sb.appendLine(returnsJson)
                        sb.appendLine("```")
                    }
                    sb.appendLine()
                    if (methodExample.isNotBlank()) {
                        sb.appendLine("**调用示例**：")
                        sb.appendLine("```js")
                        sb.appendLine(methodExample)
                        sb.appendLine("```")
                        sb.appendLine()
                    }
                }
            }
            sb.appendLine("---")
            sb.appendLine()
            toolCount++
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())
        logger.lifecycle("[generateToolsDoc] $toolCount tools → ${outputFile.relativeTo(projectDir)} (${outputFile.length()} bytes)")
    }
}

// 挂到通用 preBuild 上，preBuild 是所有变体 pre<Variant>Build 的父任务。
tasks.named("preBuild") {
    dependsOn(generateToolsDoc)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.cardview)
    implementation(libs.glide)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.fragment.ktx)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
