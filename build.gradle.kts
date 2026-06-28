import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.*
import me.kcra.takenaka.core.mapping.adapter.*
import me.kcra.takenaka.core.mapping.analysis.impl.AnalysisOptions
import me.kcra.takenaka.core.mapping.analysis.impl.MappingAnalyzerImpl
import me.kcra.takenaka.core.mapping.analysis.impl.StandardProblemKinds
import me.kcra.takenaka.core.mapping.ancestry.ConstructorComputationMode
import me.kcra.takenaka.core.mapping.ancestry.impl.collectNamespaceIds
import me.kcra.takenaka.core.mapping.ancestry.impl.computeIndices
import me.kcra.takenaka.core.mapping.resolve.impl.*
import me.kcra.takenaka.generator.common.provider.impl.*
import me.kcra.takenaka.generator.web.JDK_25_BASE_URL
import me.kcra.takenaka.generator.web.WebGenerator
import me.kcra.takenaka.generator.web.buildWebConfig
import me.kcra.takenaka.generator.web.modularClassSearchIndexOf
import me.kcra.takenaka.generator.web.transformers.CSSInliningTransformer
import me.kcra.takenaka.generator.web.transformers.MinifyingTransformer
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import kotlin.io.path.bufferedReader
import kotlin.io.path.writeText
import kotlin.io.path.writer

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
    }
}

plugins {
    id("maven-publish")
    id("me.kcra.takenaka.takenaka-plugin")
}

group = "me.kcra.takenaka" // change me
// format: <oldest version>+<newest version>[-SNAPSHOT]
// this is included in META-INF/MANIFEST.MF under Implementation-Version
// be nice to people who use the bundles and don't change the format
version = "26.1+26.3" // change me

/**
 * A three-way choice of mappings.
 */
enum class PlatformTristate(val wantsClient: Boolean, val wantsServer: Boolean) {
    CLIENT_SERVER(true, true),
    CLIENT(true, false),
    SERVER(false, true)
}

val platform = PlatformTristate.CLIENT_SERVER // change me

/**
 * The root cache workspace.
 */
val cacheWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/cache").get().asFile)
    }
}

/**
 * The shared cache workspace, mainly for manifests and maven-metadata.xml files.
 */
val sharedCacheWorkspace by lazy {
    cacheWorkspace.createWorkspace {
        name = "shared"
    }
}

/**
 * The mapping cache workspace.
 */
val mappingCacheWorkspace by lazy {
    cacheWorkspace.createCompositeWorkspace {
        name = "mappings"
    }
}

/**
 * The root web output workspace.
 */
val webWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/web").get().asFile)
    }
}

/**
 * The root bundle output workspace.
 */
val bundleWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/bundle").get().asFile)
    }
}

val mojangManifest = versionManifestOf()
val experimentalManifest = fabricVersionManifestOf()
val manifest = VersionManifest(
    mojangManifest.latest,
    mojangManifest.versions + experimentalManifest.versions
)
//val yarnProvider = YarnMetadataProvider(sharedCacheWorkspace)
val modernYarnProvider = ModernYarnMetadataProvider(sharedCacheWorkspace)
val mappingConfig = buildMappingConfig {
    version("1.21.11_unobfuscated")
    version(
        manifest
            .range("26.1", null) { // change me
                // include only releases, no snapshots
                includeTypes(Version.Type.RELEASE)
            }
            .map(Version::id)
    )
    if (manifest.latest.snapshot != manifest.latest.release) {
        version(manifest.latest.snapshot)
    }
//    version("26.3-snapshot-1") // latest snapshot, change me

    workspace(mappingCacheWorkspace)

    // remove Searge's ID namespace, it's not necessary
//    intercept { v ->
//        NamespaceFilter(v, "searge_id")
//    }
    // remove static initializers, not needed in the documentation
    intercept(::StaticInitializerFilter)
    // remove overrides of java/lang/Object, they are implicit
    intercept(::ObjectOverrideFilter)
    // remove Javadocs from mappings
//    intercept(::CommentFilter)

    contributors { versionWorkspace ->
        val mojangProvider = MojangManifestAttributeProvider(versionWorkspace)
//        val spigotProvider = SpigotManifestProvider(versionWorkspace)

        buildList {
            if (platform.wantsServer) {
                add(VanillaServerMappingContributor(versionWorkspace, mojangProvider))
                add(MojangServerMappingResolver(versionWorkspace, mojangProvider))
            }
            if (platform.wantsClient) {
                add(VanillaClientMappingContributor(versionWorkspace, mojangProvider))
                add(MojangClientMappingResolver(versionWorkspace, mojangProvider))
            }

            add(ModernIntermediaryMappingResolver(versionWorkspace, sharedCacheWorkspace))
            add(ModernYarnMappingResolver(versionWorkspace, modernYarnProvider))
//            add(IntermediaryMappingResolver(versionWorkspace, sharedCacheWorkspace))
//            add(YarnMappingResolver(versionWorkspace, yarnProvider))
//            add(
//                WrappingContributor(
//                    SeargeMappingResolver(versionWorkspace, sharedCacheWorkspace),
//                    // remove obfuscated method parameter names, they are a filler from Searge
//                    ::MethodArgSourceFilter
//                )
//            )

            // Spigot resolvers have to be last
//            if (platform.wantsServer) {
//                val link = LegacySpigotMappingPrepender.Link()
//
//                add(
//                    // 1.16.5 mappings have been republished with proper packages, even though the reobfuscated JAR does not have those
//                    // See: https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/commits/80d35549ec67b87a0cdf0d897abbe826ba34ac27
//                    link.createPrependingContributor(
//                        SpigotClassMappingResolver(versionWorkspace, spigotProvider),
//                        prependEverything = versionWorkspace.version.id == "1.16.5"
//                    )
//                )
//                add(link.createPrependingContributor(SpigotMemberMappingResolver(versionWorkspace, spigotProvider)))
//            }
        }
    }

    joinedOutputPath { workspace ->
        val fileName = when {
            platform.wantsClient && platform.wantsServer -> "client+server.tiny"
            platform.wantsClient -> "client.tiny"
            else -> "server.tiny"
        }

        workspace[fileName]
    }
}

val mappingProvider = ResolvingMappingProvider(mappingConfig, manifest)
val analyzer = MappingAnalyzerImpl(
    AnalysisOptions(
//        innerClassNameCompletionCandidates = setOf("spigot"),
//        inheritanceAdditionalNamespaces = setOf("searge") // mojang could be here too for maximal parity, but that's in exchange for a little bit of performance
        innerClassNameCompletionCandidates = setOf(),
        inheritanceAdditionalNamespaces = setOf()
    )
)

val ancestryIndexNs = "takenaka_node"
//val ancestryNamespaces = listOf("mojang", "spigot", "searge", "intermediary")
val ancestryNamespaces = listOf("modern-intermediary")

val ancestryProvider = CachedAncestryProvider(SimpleAncestryProvider(null, ancestryNamespaces))

val versionedMappingsOutputs = mappingProvider.mappingConfig.versions.map { manifest[it]!!.id to bundleWorkspace["${manifest[it]!!.id}.tiny"] }.toMap()

fun tiny2Escape(str: String): String {
    val len: Int = str.length
    var start = 0

    var toEscape = "\\\n\r\u0000\t"
    var escaped = "\\nr0t"

    val out = StringBuilder()

    for (pos in 0..<len) {
        val c: Char = str.get(pos)
        val idx = toEscape.indexOf(c)

        if (idx >= 0) {
            out.append(str, start, pos)
            out.append('\\')
            out.append(escaped[idx])
            start = pos + 1
        }
    }

    out.append(str, start, len)

    return out.toString()
}

val resolveMappings = tasks.register("resolveMappings") {
    group = "takenaka"
    description = "Resolves basic mappings for Mojang-based server development on all defined versions."

    outputs.files(versionedMappingsOutputs)

    doLast {
        val mappings = runBlocking {
            mappingProvider.get(analyzer)
                .apply {
                    analyzer.problemKinds.forEach { kind ->
                        if (kind == StandardProblemKinds.SYNTHETIC) return@forEach

                        analyzer.acceptResolutions(kind)
                    }
//                    analyzer.acceptResolutions()

                    // add ancestry indices
                    runBlocking {
                        val tree = ancestryProvider.klass<_, MappingTree.ClassMapping>(this@apply)

                        val namespaceIds = tree.collectNamespaceIds(ancestryIndexNs)
                        launch(Dispatchers.Default + CoroutineName("klass-coro")) {
                            tree.computeIndices(namespaceIds)
                        }

                        tree.forEach { node ->
                            launch(Dispatchers.Default + CoroutineName("field-coro")) {
                                ancestryProvider.field<_, _, MappingTree.FieldMapping>(node).computeIndices(namespaceIds)
                            }
                            launch(Dispatchers.Default + CoroutineName("method-coro")) {
                                ancestryProvider.method<_, _, MappingTree.MethodMapping>(node, constructorMode = ConstructorComputationMode.INCLUDE).computeIndices(namespaceIds)
                            }
                        }
                    }
                }
        }

        // make bundleable mappings
        mappings.forEach { (version, tree) ->
            val metadataList = tree.metadata.map { it.key to tiny2Escape(it.value!!) }.toList()
            tree.metadata.clear()
            metadataList.forEach { (key, value) -> (tree as MemoryMappingTree).visitMetadata(key, value) }

            Tiny2FileWriter(versionedMappingsOutputs[version.id]!!.writer(), false)
                .use { w -> tree.accept(MissingDescriptorFilter(w)) }
        }
    }
}

val clean = tasks.register("clean") {
    group = "takenaka"
    description = "Removes all build artifacts."

    doLast {
        layout.buildDirectory.get().asFile.deleteRecursively()
    }
}

val createBundle = tasks.register<Jar>("createBundle") {
    group = "takenaka"
    description = "Creates a JAR bundle of mappings for all defined versions."

    dependsOn(resolveMappings)

    from(bundleWorkspace.rootDirectory)
    archiveBaseName.set("bundle") // overridden by the Maven publication, doesn't matter
    destinationDirectory.set(project.layout.buildDirectory.dir("takenaka"))

    manifest {
        attributes(mapOf(
            "Implementation-Version" to project.version
        ))
    }
}

//val copyMain by tasks.registering(Copy::class) {
//    group = "takenaka"
//    description = "Copies the main page notice."
//
//    from("index.html")
//    into(webWorkspace.rootDirectory)
//
//    doFirst {
//        webWorkspace["index.html"].moveTo(webWorkspace["main.html"], overwrite = true)
//    }
//}

val webConfig = buildWebConfig {
    val chosenMappings = when {
        platform.wantsClient && platform.wantsServer -> "client- and server-side"
        platform.wantsClient -> "client-side"
        else -> "server-side"
    }

    welcomeMessage(
        """
            <h1>Welcome to the browser for Minecraft: Java Edition $chosenMappings mappings!</h1>
            <br/>
            <p>
                You can move through this site by following links to specific versions/packages/classes/...
                or use the nifty search field in the top right corner (appears when in a versioned page!).
            </p>
            <br/>
            <p>
                It is possible that there are errors in mappings displayed here, but we've tried to make them as close as possible to the runtime naming.<br/>
                If you run into such an error, please report it at <a href="https://github.com/RelativityMC/takenaka/issues/new">the issue tracker</a>!
            </p>
            <br/>
            <!-- <strong>NOTE: This build of the site excludes synthetic members (generated by the compiler, i.e. not in the source code).</strong> -->
        """.trimIndent()
    )

    transformer(CSSInliningTransformer("cdn.jsdelivr.net"))
//    transformer(MinifyingTransformer())
    index(modularClassSearchIndexOf(JDK_25_BASE_URL))

    replaceCraftBukkitVersions("spigot")
    friendlyNamespaces("modern-yarn", "modern-intermediary", "yarn", "intermediary", "mojang", "spigot", "searge", "source")
    // namespace("mojang", "Mojang", "#4D7C0F", AbstractMojangMappingResolver.META_LICENSE)
    namespace("spigot", "Spigot", "#CA8A04", AbstractSpigotMappingResolver.META_LICENSE)
    namespace("yarn", "Yarn", "#626262", YarnMappingResolver.META_LICENSE)
    namespace("searge", "Searge", "#B91C1C", SeargeMappingResolver.META_LICENSE)
    namespace("intermediary", "Intermediary", "#0369A1", IntermediaryMappingResolver.META_LICENSE)
    namespace("modern-intermediary", "Modern Intermediary", "#0369A1", ModernIntermediaryMappingResolver.META_LICENSE)
    namespace("modern-yarn", "Modern Yarn", "#626262", ModernYarnMappingResolver.META_LICENSE)
    namespace("source", "Official", "#581C87")
}

val generator = WebGenerator(webWorkspace, webConfig)
val buildWeb = tasks.register("buildWeb") {
    group = "takenaka"
    description = "Builds a web documentation site for mappings of all defined versions."

    dependsOn(resolveMappings)
    inputs.files(versionedMappingsOutputs.values)
//    finalizedBy(copyMain)
    doLast {
        runBlocking {
            val mappings = versionedMappingsOutputs.map { (version, mappingPath) ->
                val mappingTree = MemoryMappingTree()
                Tiny2FileReader.read(mappingPath.bufferedReader(), mappingTree)

                manifest[version]!! to mappingTree
            }.toMap()

            @Suppress("UNCHECKED_CAST")
            generator.generate(
                SimpleMappingProvider(mappings),
                ancestryProvider
            )
        }
    }
    doLast {
        webWorkspace[".nojekyll"].writeText("")
        webWorkspace["CNAME"].writeText("mappings.relativitymc.org") // change me, remove if you want to build for a *.github.io domain
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBundle") {
            artifact(createBundle)
            pom {
                name.set("mappings")
                description.set("A mapping bundle with a basic set of mappings for Mojang-based server and client development.")
                url.set("https://github.com/RelativityMC/mappings") // change me
//                developers {
//                    developer {
//                        id.set("zlataovce")
//                        name.set("Matouš Kučera")
//                        email.set("mk@kcra.me")
//                    }
//                }
                scm {
                    connection.set("scm:git:github.com/RelativityMC/mappings.git") // change me
                    developerConnection.set("scm:git:ssh://github.com/RelativityMC/mappings.git") // change me
                    url.set("https://github.com/RelativityMC/mappings/tree/main") // change me
                }
            }
        }
    }

    repositories {
//        maven {
//            url = uri(
//                if ((project.version as String).endsWith("-SNAPSHOT")) {
//                    "https://repo.screamingsandals.org/snapshots" // change me
//                } else {
//                    "https://repo.screamingsandals.org/releases" // change me
//                }
//            )
//            credentials {
//                // make sure to add the `REPO_USERNAME` and `REPO_PASSWORD` secrets to the repository
//                username = System.getenv("REPO_USERNAME")
//                password = System.getenv("REPO_PASSWORD")
//            }
//        }
    }
}
