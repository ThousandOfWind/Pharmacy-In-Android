import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

// Only public build inputs are read. No credentials, machine paths or account identities enter assets.
val legalSource = layout.projectDirectory.dir("src/main/legal")
listOf("debug", "release").forEach { variant ->
    val output = layout.buildDirectory.dir("generated/noticeAssets/$variant")
    val generate = tasks.register("generate${variant.replaceFirstChar { it.uppercase() }}NoticeAssets") {
        // Resolve afresh on each build: notices must match this variant's current dependency graph.
        outputs.dir(output)
        outputs.upToDateWhen { false }
        doLast {
            val target = output.get().dir("legal").asFile.apply { mkdirs() }
            val root = rootProject.projectDir
            listOf("LICENSE" to "project-license.txt", "docs/privacy.md" to "privacy.txt").forEach { (source, name) ->
                File(root, source).copyTo(File(target, name), overwrite = true)
            }
            listOf("Apache-2.0.txt", "MPL-2.0.txt", "OpenAI-Kotlin-MIT.txt", "SLF4J-MIT.txt").forEach {
                legalSource.file(it).asFile.copyTo(File(target, it), overwrite = true)
            }
            val configuration = configurations.getByName("${variant}RuntimeClasspath")
            val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.sortedBy { "${it.moduleVersion.id}:${it.name}" }
            val modules = artifacts.map { it.id.componentIdentifier }.filterIsInstance<ModuleComponentIdentifier>().distinct()
            val poms = dependencies.createArtifactResolutionQuery().forComponents(modules)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
                .resolvedComponents.associate { component ->
                    component.id to component.getArtifacts(MavenPomArtifact::class.java)
                        .filterIsInstance<ResolvedArtifactResult>().single().file
                }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
            val vendor = sortedMapOf<String, String>()
            val vendorNames = mutableMapOf<String, MutableSet<String>>()
            val index = StringBuilder("第三方组件与通知\n\n仅列本变体实际解析的运行时组件；供应商通知可能含其更广泛的内嵌组件清单。\n")
            artifacts.distinctBy { it.moduleVersion.id }.forEach { artifact ->
                val id = artifact.moduleVersion.id
                val coordinate = "${id.group}:${id.name}:${id.version}"
                val pom = factory.newDocumentBuilder().parse(checkNotNull(poms[artifact.id.componentIdentifier]) { "Missing POM: $coordinate" })
                val licenseNodes = pom.getElementsByTagName("license")
                val labels = (0 until licenseNodes.length).map { licenseNodes.item(it).textContent.trim().replace(Regex("\\s+"), " ") }
                val category = when {
                    labels.isNotEmpty() && labels.all { it.contains("ML Kit Terms of Service") } -> "ML Kit Terms: https://developers.google.com/ml-kit/terms (incorporates Google APIs Terms: https://developers.google.com/terms)"
                    labels.isNotEmpty() && labels.all { it.contains("Android Software Development Kit License") } -> "Android SDK License: https://developer.android.com/studio/terms"
                    id.group == "com.aallam.openai" && id.version == "4.1.0" && labels.isNotEmpty() && labels.all { it.contains("MIT License") } -> "MIT: OpenAI-Kotlin-MIT.txt"
                    coordinate == "org.slf4j:slf4j-api:2.0.16" && labels.isEmpty() -> "MIT: SLF4J-MIT.txt"
                    coordinate == "com.google.guava:listenablefuture:1.0" && labels.isEmpty() -> "Apache-2.0.txt (Guava): https://github.com/google/guava/blob/v18.0/COPYING"
                    coordinate == "javax.inject:javax.inject:1" && labels.isEmpty() -> "Apache-2.0.txt (JSR-330): https://github.com/javax-inject/javax-inject/blob/master/LICENSE"
                    labels.isNotEmpty() && labels.all { it.contains("Apache", ignoreCase = true) && it.contains("2.0") } -> "Apache-2.0.txt"
                    else -> error("Unmapped license for $coordinate: $labels; review before distributing")
                }
                index.append("\n$coordinate\n$category\n")
                if (labels.isNotEmpty()) index.append("POM license: ${labels.joinToString("; ")}\n")
                // Retain declared developer/organization attribution without claiming it proves ownership.
                listOf("organization", "developer").forEach { tag ->
                    val nodes = pom.getElementsByTagName(tag)
                    (0 until nodes.length).forEach { i ->
                        index.append("POM attribution: ${nodes.item(i).textContent.trim().replace(Regex("\\s+"), " ")}\n")
                    }
                }
            }
            // Process every artifact (including secondary classifier artifacts), retaining notices verbatim.
            artifacts.forEach { artifact ->
                val coordinate = artifact.moduleVersion.id.toString()
                java.util.zip.ZipFile(artifact.file).use { zip ->
                    fun retain(name: String, bytes: ByteArray) {
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        vendor[hash] = bytes.toString(Charsets.UTF_8)
                        vendorNames.getOrPut(hash) { sortedSetOf() }.add("$coordinate / $name")
                    }
                    zip.getEntry("third_party_licenses.json")?.let { entry ->
                        val data = zip.getInputStream(checkNotNull(zip.getEntry("third_party_licenses.txt"))).use { it.readBytes() }
                        val entries = zip.getInputStream(entry).use { JsonSlurper().parse(it) as Map<*, *> }
                        entries.forEach { (name, value) ->
                            val offset = value as Map<*, *>
                            val start = (offset["start"] as Number).toInt()
                            val length = (offset["length"] as Number).toInt()
                            check(start >= 0 && length > 0 && start.toLong() + length <= data.size) { "Invalid vendor notice: $coordinate" }
                            retain(name.toString(), data.copyOfRange(start, start + length))
                        }
                    }
                    zip.getEntry("okhttp3/internal/publicsuffix/publicsuffixes.gz")?.let { entry ->
                        // Provide the exact bundled rules in readable form, not only a mutable upstream URL.
                        check(coordinate == "com.squareup.okhttp3:okhttp:4.12.0") { "Review Public Suffix source on upgrade" }
                        val rules = zip.getInputStream(entry).use { raw ->
                            java.io.DataInputStream(java.util.zip.GZIPInputStream(raw)).use { input ->
                                fun section(): String {
                                    val size = input.readInt()
                                    check(size in 1..1024 * 1024) { "Invalid Public Suffix source" }
                                    return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
                                }
                                val normal = section()
                                val exceptions = section()
                                check(input.read() == -1)
                                "// Public Suffix rules from OkHttp 4.12.0; MPL-2.0.\n" +
                                    "// Normal rules followed by exception rules prefixed with !.\n" + normal +
                                    exceptions.lineSequence().filter { it.isNotBlank() }.joinToString("\n", postfix = "\n") { "!$it" }
                            }
                        }
                        File(target, "public-suffix-source.txt").writeText(rules, Charsets.UTF_8)
                        retain("Bundled Public Suffix rules (MPL-2.0)", rules.toByteArray(Charsets.UTF_8))
                    }
                    zip.entries().asSequence().filter { entry ->
                        !entry.isDirectory && !entry.name.startsWith("third_party_licenses") &&
                            Regex("(^|/)(LICENSE[^/]*|NOTICE[^/]*|AL2\\.0|LGPL2\\.1)$", RegexOption.IGNORE_CASE).containsMatchIn(entry.name)
                    }.forEach { entry -> retain(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }
                }
            }
            index.append("\n字体：assets/font-licenses/WenKai-OFL.txt、LongCang-OFL.txt（SIL OFL 1.1）。\n")
            index.append("Public Suffix List: MPL-2.0.txt；来源 https://publicsuffix.org/list/public_suffix_list.dat。\n")
            index.append("OkHttp 4.12.0 内的 publicsuffixes.gz 随原包分发；同版本规则以可读文本随包提供在 public-suffix-source.txt 及本文末尾。源码/许可入口：https://github.com/square/okhttp/tree/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix。\n")
            listOf("Apache-2.0.txt", "MPL-2.0.txt", "OpenAI-Kotlin-MIT.txt", "SLF4J-MIT.txt").forEach { name ->
                index.append("\n====================\n$name\n====================\n${legalSource.file(name).asFile.readText(Charsets.UTF_8)}\n")
            }
            listOf("WenKai-OFL.txt", "LongCang-OFL.txt").forEach { name ->
                index.append("\n====================\n$name\n====================\n${file("src/main/assets/font-licenses/$name").readText(Charsets.UTF_8)}\n")
            }
            index.append("\n下列为供应商附带的原始许可和版权文本（按内容去重，保留全部来源）。\n")
            vendor.forEach { (hash, text) ->
                index.append("\n====================\n${vendorNames.getValue(hash).joinToString("\n")}\n====================\n$text\n")
            }
            File(target, "third-party-notices.txt").writeText(index.toString(), Charsets.UTF_8)
        }
    }
    val capitalized = variant.replaceFirstChar { it.uppercase() }
    tasks.matching {
        it.name == "pre${capitalized}Build" || it.name == "merge${capitalized}Assets" ||
            it.name == "lintAnalyze$capitalized" ||
            (it.name.startsWith("generate$capitalized") && it.name.contains("Lint"))
    }.configureEach { dependsOn(generate) }
}
