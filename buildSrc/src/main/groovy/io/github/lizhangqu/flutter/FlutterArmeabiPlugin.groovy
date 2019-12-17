package io.github.lizhangqu.flutter

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency

import java.nio.file.Path
import java.nio.file.Paths

import com.android.builder.model.AndroidProject
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Jar

import java.util.zip.ZipFile

/**
 * @author lizhangqu
 * @version V1.0
 * @since 2019-02-28 20:08
 */
class FlutterArmeabiPlugin implements Plugin<Project> {
    private File flutterRoot
    private File flutterExecutable
    private Properties localProperties

    private File flutterJar
    private File debugFlutterJar
    private File profileFlutterJar
    private File releaseFlutterJar
    private File dynamicProfileFlutterJar
    private File dynamicReleaseFlutterJar

    // The name prefix for flutter builds.  This is used to identify gradle tasks
    // where we expect the flutter tool to provide any error output, and skip the
    // standard Gradle error output in the FlutterEventLogger. If you change this,
    // be sure to change any instances of this string in symbols in the code below
    // to match.
    static final String flutterBuildPrefix = "flutterBuild"

    private static Properties readPropertiesIfExist(File propertiesFile) {
        Properties result = new Properties()
        if (propertiesFile.exists()) {
            propertiesFile.withReader('UTF-8') { reader -> result.load(reader) }
        }
        return result
    }

    private String resolveProperty(Project project, String name, String defaultValue) {
        if (localProperties == null) {
            localProperties = readPropertiesIfExist(new File(project.projectDir.parentFile, "local.properties"))
        }
        String result
        if (project.hasProperty(name)) {
            result = project.property(name)
        }
        if (result == null) {
            result = localProperties.getProperty(name)
        }
        if (result == null) {
            result = defaultValue
        }
        return result
    }

    @Override
    void apply(Project project) {
        String flutterRootPath = resolveProperty(project, "flutter.sdk", System.env.FLUTTER_ROOT)
        if (flutterRootPath == null) {
            project.logger.error("Flutter SDK not found. Define location with flutter.sdk in the local.properties file or with a FLUTTER_ROOT environment variable.")
            return
        }
        flutterRoot = project.file(flutterRootPath)
        if (!flutterRoot.isDirectory()) {
            throw new GradleException("flutter.sdk must point to the Flutter SDK directory")
        }

        String flutterExecutableName = Os.isFamily(Os.FAMILY_WINDOWS) ? "flutter.bat" : "flutter"
        flutterExecutable = Paths.get(flutterRoot.absolutePath, "bin", flutterExecutableName).toFile();

        if (project.hasProperty('localEngineOut')) {
            String engineOutPath = project.property('localEngineOut')
            File engineOut = project.file(engineOutPath)
            if (!engineOut.isDirectory()) {
                throw new GradleException('localEngineOut must point to a local engine build')
            }
            flutterJar = Paths.get(engineOut.absolutePath, "flutter.jar").toFile()
            if (!flutterJar.isFile()) {
                throw new GradleException('Local engine build does not contain flutter.jar')
            }
            project.android.buildTypes.each {
                addFlutterApiDependency(project, it, createFlutterArmeabiJarTask(project, it))
            }
        } else {
            Path baseEnginePath = Paths.get(flutterRoot.absolutePath, "bin", "cache", "artifacts", "engine")
            String targetArch = 'arm'
            if (project.hasProperty('target-platform') &&
                    project.property('target-platform') == 'android-arm64') {
                targetArch = 'arm64'
            }
            debugFlutterJar = baseEnginePath.resolve("android-${targetArch}").resolve("flutter.jar").toFile()
            profileFlutterJar = baseEnginePath.resolve("android-${targetArch}-profile").resolve("flutter.jar").toFile()
            releaseFlutterJar = baseEnginePath.resolve("android-${targetArch}-release").resolve("flutter.jar").toFile()
            dynamicProfileFlutterJar = baseEnginePath.resolve("android-${targetArch}-dynamic-profile").resolve("flutter.jar").toFile()
            dynamicReleaseFlutterJar = baseEnginePath.resolve("android-${targetArch}-dynamic-release").resolve("flutter.jar").toFile()
            project.android.buildTypes.each {
                addFlutterApiDependency(project, it, createFlutterArmeabiJarTask(project, it))
            }
            project.android.buildTypes.whenObjectAdded {
                addFlutterApiDependency(project, it, createFlutterArmeabiJarTask(project, it))
            }
        }

        project.afterEvaluate {
            def variants
            if (project.android.hasProperty("applicationVariants")) {
                variants = project.android.applicationVariants
            } else {
                variants = project.android.libraryVariants
            }
            variants.all { variant ->
                addFlutterApiDependency(project, variant, createFlutterArmeabiSnapshotsTask(project, variant))
            }
        }
    }


    /**
     * create flutter armeabi jar task
     */
    private Task createFlutterArmeabiSnapshotsTask(Project project, def variant) {
        def compileFlutterBuildArmTask = project.tasks.findByName("compileflutterBuild${variant.name.capitalize()}Arm")
        if (compileFlutterBuildArmTask == null) {
            compileFlutterBuildArmTask = project.tasks.findByName("compileFlutterBuild${variant.name.capitalize()}Arm")
        }
        if (compileFlutterBuildArmTask == null) {
            compileFlutterBuildArmTask = project.tasks.findByName("compileFlutterBuild${variant.name.capitalize()}")
        }
        if (compileFlutterBuildArmTask == null) {
            return null
        }
        String taskName = "${flutterBuildPrefix}${variant.name.capitalize()}ArmeabiSnapshots"
        if (project.tasks.findByName(taskName) != null) {
            return null
        }
        File flutterArmJar = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/flutter/armeabi/${variant.name}/flutter-snapshots.jar")

        def flutterArmeabiSnapshotsTask = project.tasks.create(taskName, Jar) {
            dependsOn compileFlutterBuildArmTask
            destinationDir flutterArmJar.parentFile
            archiveName flutterArmJar.name
            from(compileFlutterBuildArmTask.intermediateDir) {
                include '*.so'
                rename { String filename ->
                    return "lib/armeabi/lib${filename}"
                }
            }
            //v1.12.13
            from(new File(compileFlutterBuildArmTask.intermediateDir, "armeabi-v7a")) {
                include '*.so'
                rename { String filename ->
                    return "lib/armeabi/lib${filename}"
                }
            }
        }
        return flutterArmeabiSnapshotsTask
    }

    /**
     * create flutter armeabi jar task
     */
    private Task createFlutterArmeabiJarTask(Project project, def variant) {
        String taskName = "${flutterBuildPrefix}${variant.name.capitalize()}ArmeabiJar"
        if (project.tasks.findByName(taskName) != null) {
            return null
        }
        File flutterJar = flutterJarFor(buildModeFor(variant))
        if (flutterJar == null || !flutterJar.exists()) {
            //v1.12.13可能不存在这个文件，使用的远程依赖
            File versionFile = new File(flutterRoot, "version")
            if (!versionFile.exists()) {
                return
            }
            String flutterVersion = versionFile.text.trim()
            String[] flutterVersionSpilt = flutterVersion.split("\\.")
            //>=v1.12.13
            if (!(flutterVersionSpilt.length > 2 && (flutterVersionSpilt[0].toInteger() > 1 || (flutterVersionSpilt[0].toInteger() <= 1 && flutterVersionSpilt[1].toInteger() >= 12)))) {
                return
            }
            String configuration
            if (project.getConfigurations().findByName("api")) {
                configuration = "${variant.name}Api"
            } else {
                configuration = "${variant.name}Compile"
            }

            project.getConfigurations().findByName(configuration)?.copy()?.dependencies?.each { def dependency ->
                if (dependency.group == "io.flutter" && dependency.name == "armeabi_v7a_${variant.name}") {
                    String armeabiV7AFlutterSO = "${dependency.group}:${dependency.name}:${dependency.version}"
                    File mavenFile = getMavenFile(project, armeabiV7AFlutterSO)
                    if (mavenFile != null && mavenFile.exists()) {
                        flutterJar = mavenFile

                        //aar中也添加so和jar，如果文件冲突了，请exclude掉flutter的远程依赖
                        project.afterEvaluate {
                            project.tasks.findByName("assemble${variant.name.capitalize()}")?.doLast {
                                File flutterAar = new File(project.buildDir, "outputs/aar/flutter-${variant.name}.aar")
                                if (!flutterAar.exists()) {
                                    flutterAar = new File(project.buildDir, "outputs/aar/flutter.aar")
                                }
                                if (flutterAar.exists()) {
                                    boolean shouldAddRemoteDependency = !ZipUtil.containsAnyEntry(flutterAar, [
                                            "jni/arm64-v8a/libflutter.so",
                                            "jni/armeabi-v7a/libflutter.so",
                                            "jni/x86/libflutter.so",
                                            "jni/x86_64/libflutter.so",
                                            "lib/arm64-v8a/libflutter.so",
                                            "lib/armeabi-v7a/libflutter.so",
                                            "lib/x86/libflutter.so",
                                            "lib/x86_64/libflutter.so"
                                    ].toArray(new String[0]))
                                    //1.12.13 jar包远程依赖
                                    if (shouldAddRemoteDependency) {
                                        ZipUtil.addEntry(flutterAar, "libs/flutter_embedding.jar", getMavenFile(project, "${dependency.group}:flutter_embedding_${variant.name}:${dependency.version}"))
                                        ZipUtil.addEntry(flutterAar, "jni/armeabi-v7a/libflutter.so", ZipUtil.unpackEntry(getMavenFile(project, "${dependency.group}:armeabi_v7a_${variant.name}:${dependency.version}"), "lib/armeabi-v7a/libflutter.so"))
                                        if (!ZipUtil.containsEntry(flutterAar, "jni/armeabi/libflutter.so")) {
                                            ZipUtil.addEntry(flutterAar, "jni/armeabi/libflutter.so", ZipUtil.unpackEntry(getMavenFile(project, "${dependency.group}:armeabi_v7a_${variant.name}:${dependency.version}"), "lib/armeabi-v7a/libflutter.so"))
                                        }
                                        ZipUtil.addEntry(flutterAar, "jni/arm64-v8a/libflutter.so", ZipUtil.unpackEntry(getMavenFile(project, "${dependency.group}:arm64_v8a_${variant.name}:${dependency.version}"), "lib/arm64-v8a/libflutter.so"))
                                        if (variant.name.contains("debug")) {
                                            ZipUtil.addEntry(flutterAar, "jni/x86/libflutter.so", ZipUtil.unpackEntry(getMavenFile(project, "${dependency.group}:x86_${variant.name}:${dependency.version}"), "lib/x86/libflutter.so"))
                                            ZipUtil.addEntry(flutterAar, "jni/x86_64/libflutter.so", ZipUtil.unpackEntry(getMavenFile(project, "${dependency.group}:x86_64_${variant.name}:${dependency.version}"), "lib/x86_64/libflutter.so"))
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
        if (flutterJar == null || !flutterJar.exists()) {
            return
        }

        //如果已经包含armeabi，则不创建
        ZipFile zipFile = null
        try {
            boolean alreadyContainsArmeabi = ((zipFile = new ZipFile(flutterJar)).getEntry("lib/armeabi/libflutter.so") != null)
            if (alreadyContainsArmeabi) {
                return null
            }
        } finally {
            if (zipFile != null) {
                zipFile.close()
            }
        }

        File flutterArmJar = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/flutter/armeabi/${variant.name}/flutter-armeabi.jar")
        def flutterArmeabiJarTask = project.tasks.create(taskName, Jar) {
            destinationDir flutterArmJar.parentFile
            archiveName flutterArmJar.name
            from(project.zipTree(flutterJar).matching {
                //仅包含armeabi-v7a的so
                include "lib/armeabi-v7a/libflutter.so"
            }) {
                eachFile { def fcp ->
                    if (fcp.relativePath.pathString.contains("lib/armeabi-v7a/libflutter.so")) {
                        //重命名路径到armeabi目录
                        fcp.path = "lib/armeabi/libflutter.so"
                    } else {
                        //非so排除
                        fcp.exclude()
                    }
                }
            }
        }
        return flutterArmeabiJarTask
    }

    /**
     * add armeabi so for prebuilt engine
     */
    private void addFlutterApiDependency(Project project, def variant, def task) {
        if (task == null) {
            return
        }
        Task javacTask = project.tasks.findByName("compile${variant.name.capitalize()}JavaWithJavac")
        if (javacTask) {
            javacTask.dependsOn task
        }
        Task kotlinTask = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
        if (kotlinTask) {
            kotlinTask.dependsOn task
        }

        project.dependencies {
            String configuration;
            if (project.getConfigurations().findByName("api")) {
                configuration = variant.name + "Api"
            } else {
                configuration = variant.name + "Compile"
            }
            add(configuration, project.files {
                task
            })
        }
    }

    /**
     * Returns a Flutter build mode suitable for the specified Android buildType.
     *
     * Note: The BuildType DSL type is not public, and is therefore omitted from the signature.
     *
     * @return "debug", "profile", "dynamicProfile", "dynamicRelease", or "release" (fall-back).
     */
    private static String buildModeFor(buildType) {
        if (buildType.name == "profile") {
            return "profile"
        } else if (buildType.name == "dynamicProfile") {
            return "dynamicProfile"
        } else if (buildType.name == "dynamicRelease") {
            return "dynamicRelease"
        } else if (buildType.debuggable) {
            return "debug"
        }
        return "release"
    }

    /**
     * Returns a Flutter Jar file path suitable for the specified Android buildMode.
     */
    private File flutterJarFor(buildMode) {
        if (flutterJar != null) {
            return flutterJar
        }
        if (buildMode == "profile") {
            return profileFlutterJar
        } else if (buildMode == "dynamicProfile") {
            return dynamicProfileFlutterJar
        } else if (buildMode == "dynamicRelease") {
            return dynamicReleaseFlutterJar
        } else if (buildMode == "debug") {
            return debugFlutterJar
        }
        return releaseFlutterJar
    }


    /**
     * get maven file
     */
    private static File getMavenFile(Project project, def maven) {
        Dependency dependency = project.getDependencies().create(maven)
        try {
            dependency.setChanging(true)
        } catch (Exception e) {
            e.printStackTrace()
        }
        Configuration configuration = project.getConfigurations().detachedConfiguration(dependency)
        configuration.setTransitive(false)
        configuration.resolutionStrategy.cacheDynamicVersionsFor(5, 'minutes')
        configuration.resolutionStrategy.cacheChangingModulesFor(0, 'seconds')
        File file = configuration.getSingleFile()
        return file
    }
}
